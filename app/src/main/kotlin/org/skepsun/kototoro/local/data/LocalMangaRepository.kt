package org.skepsun.kototoro.local.data

import android.content.Context
import androidx.core.content.edit
import androidx.core.net.toFile
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toCollection
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.skepsun.kototoro.core.model.LocalMangaSource
import org.skepsun.kototoro.core.model.isLocal
import org.skepsun.kototoro.core.model.isNsfw
import org.skepsun.kototoro.core.model.getContentType
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.parsers.model.NovelChapterContent
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.util.AlphanumComparator
import org.skepsun.kototoro.core.util.ext.URI_SCHEME_PDF
import org.skepsun.kototoro.core.util.ext.extractPdfPath
import org.skepsun.kototoro.core.util.ext.isPdfUriString
import org.skepsun.kototoro.core.util.ext.deleteAwait
import org.skepsun.kototoro.core.util.ext.creationTime
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.core.util.ext.takeIfWriteable
import org.skepsun.kototoro.core.util.ext.withChildren
import org.skepsun.kototoro.local.data.index.LocalContentIndex
import org.skepsun.kototoro.local.data.input.LocalContentParser
import org.skepsun.kototoro.local.data.output.LocalContentOutput
import org.skepsun.kototoro.local.data.output.LocalContentUtil
import org.skepsun.kototoro.local.domain.ContentLock
import org.skepsun.kototoro.local.domain.model.LocalContent
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentListFilterCapabilities
import org.skepsun.kototoro.parsers.model.ContentListFilterOptions
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.util.levenshteinDistance
import org.skepsun.kototoro.parsers.util.longHashCode
import org.skepsun.kototoro.parsers.util.mapToSet
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import java.io.File
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

private const val MAX_PARALLELISM = 4
private const val FILENAME_SKIP = ".notamanga"
private const val BACKUP_SUFFIX = ".bk"
private const val SCAN_SIGNATURE_PREFS = "_local_scan_signature"
private const val KEY_SCAN_SIGNATURE = "signature"

/** 本地列表每页构建的内容数量（与 UI 分页页大小一致）。 */
private const val LOCAL_PAGE_SIZE = 30

/** 本地列表排序模式（分页栏排序菜单）。 */
enum class LocalPagesSortMode {
	DEFAULT,
	PAGES_ASC,
	PAGES_DESC,
}

@Singleton
class LocalMangaRepository @Inject constructor(
	private val storageManager: LocalStorageManager,
	private val localContentIndex: LocalContentIndex,
	@LocalStorageChanges private val localStorageChanges: MutableSharedFlow<LocalContent?>,
	private val settings: AppSettings,
	private val lock: ContentLock,
	private val repositoryFactory: Provider<ContentRepository.Factory>,
	@ApplicationContext private val context: Context,
	private val mangaDataRepository: ContentDataRepository,
) : ContentRepository {

	private val scanSignaturePrefs = context.getSharedPreferences(SCAN_SIGNATURE_PREFS, Context.MODE_PRIVATE)

	override val source = LocalMangaSource

	override val filterCapabilities: ContentListFilterCapabilities
		get() = ContentListFilterCapabilities(
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
		)

	override val sortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.ALPHABETICAL,
		SortOrder.RATING,
		SortOrder.NEWEST,
		SortOrder.RELEVANCE,
	)

	override var defaultSortOrder: SortOrder
		get() = settings.localListOrder
		set(value) {
			settings.localListOrder = value
		}

	override suspend fun getFilterOptions() = ContentListFilterOptions(
		availableTags = localContentIndex.getAvailableTags(
			skipNsfw = settings.isNsfwContentDisabled,
		).mapToSet { ContentTag(title = it, key = it, source = source) },
		availableContentRating = if (!settings.isNsfwContentDisabled) {
			EnumSet.of(ContentRating.SAFE, ContentRating.ADULT)
		} else {
			emptySet()
		},
		availableContentTypes = EnumSet.of(ContentType.MANGA, ContentType.NOVEL, ContentType.VIDEO),
	)

	// 有序过滤后的 mangaId 缓存（方案 B：打开只构建第一页，翻页才构建下一页）
	private var orderedIdsCache: List<Long>? = null
	private var orderedCacheKey: String? = null

	/** 页数缓存：mangaId → 总页数（PDF 等，后台统计）。 */
	private val pagesCache = ConcurrentHashMap<Long, Int>()

	/** 分页栏排序模式（默认 / 按页数升序 / 按页数降序）。 */
	@Volatile
	var pagesSortMode: LocalPagesSortMode = LocalPagesSortMode.DEFAULT

	/** 已统计的页数（供列表模型附加显示）。 */
	suspend fun getPagesById(): Map<Long, Int> = pagesCache.toMap()

	private data class LightEntry(
		val mangaId: Long,
		val title: String,
		val rating: Float,
		val createdAt: Long,
	)

	override suspend fun getList(offset: Int, order: SortOrder?, filter: ContentListFilter?): List<Content> {
		val key = buildOrderCacheKey(order, filter)
		if (orderedIdsCache == null || orderedCacheKey != key) {
			orderedIdsCache = buildOrderedIds(order, filter)
			orderedCacheKey = key
		}
		val ids = orderedIdsCache ?: return emptyList()
		if (offset >= ids.size) return emptyList()
		val end = (offset + LOCAL_PAGE_SIZE).coerceAtMost(ids.size)
		val pageIds = ids.subList(offset, end)
		// 只对当前页批量读完整内容 + 文件存在性检查
		val cachedById = mangaDataRepository.findContentByIds(pageIds)
		return pageIds.mapNotNull { id ->
			val content = cachedById[id] ?: return@mapNotNull null
			if (org.skepsun.kototoro.local.domain.model.LocalContent(content).file.exists()) content else null
		}
	}

	/**
	 * 轻量全量扫描：并行 stat 文件（过滤已删除 + 收集创建时间）、批量读轻量列与标签，
	 * 完成过滤与排序后只返回有序 mangaId 列表（不构建完整 Content）。
	 */
	private suspend fun buildOrderedIds(order: SortOrder?, filter: ContentListFilter?): List<Long> {
		val indexed = localContentIndex.getAllIndexed()
		if (indexed.isEmpty()) return emptyList()
		val creations = statCreationTimes(indexed.map { it.path })
		val alive = indexed.filter { it.path in creations }
		if (alive.isEmpty()) return emptyList()
		val ids = alive.map { it.mangaId }
		val lightById = mangaDataRepository.findLightByIds(ids).associateBy { it.mangaId }
		val tagsById = mangaDataRepository.findTagTitlesByMangaIds(ids)
		val skipNsfw = settings.isNsfwContentDisabled
		val query = filter?.query
		val tags = filter?.tags.orEmpty().mapToSet { it.title }
		val types = filter?.types.orEmpty()
		val tagsExclude = filter?.tagsExclude.orEmpty().mapToSet { it.title }
		val ratingFilter = filter?.contentRating?.singleOrNull()
		val entries = alive.mapNotNull { entry ->
			val light = lightById[entry.mangaId] ?: return@mapNotNull null
			if (skipNsfw && light.isNsfw) return@mapNotNull null
			if (!query.isNullOrEmpty() && !light.matchesQuery(query)) return@mapNotNull null
			if (tags.isNotEmpty() && !tagsById[entry.mangaId].orEmpty().containsAll(tags)) return@mapNotNull null
			if (types.isNotEmpty() && (light.contentType?.let { runCatching { ContentType.valueOf(it) }.getOrNull() } ?: ContentType.MANGA) !in types) {
				return@mapNotNull null
			}
			if (tagsExclude.isNotEmpty() && tagsById[entry.mangaId].orEmpty().any { it in tagsExclude }) {
				return@mapNotNull null
			}
			ratingFilter?.let {
				val isNsfwFilter = it == ContentRating.ADULT
				if (light.isNsfw != isNsfwFilter) return@mapNotNull null
			}
			LightEntry(entry.mangaId, light.title, light.rating, creations[entry.path] ?: 0L)
		}.toMutableList()
		when {
			pagesSortMode == LocalPagesSortMode.PAGES_ASC ->
				entries.sortBy { pagesCache[it.mangaId] ?: Int.MAX_VALUE }
			pagesSortMode == LocalPagesSortMode.PAGES_DESC ->
				entries.sortByDescending { pagesCache[it.mangaId] ?: -1 }
			else -> when (order) {
				SortOrder.ALPHABETICAL -> entries.sortWith(compareBy(AlphanumComparator()) { it.title })
				SortOrder.RATING -> entries.sortByDescending { it.rating }
				SortOrder.NEWEST,
				SortOrder.UPDATED -> entries.sortWith(compareBy({ -it.createdAt }, { it.mangaId }))
				else -> Unit
			}
		}
		if (!query.isNullOrEmpty() && order == SortOrder.RELEVANCE && pagesSortMode == LocalPagesSortMode.DEFAULT) {
			entries.sortBy { it.title.levenshteinDistance(query) }
		}
		return entries.map { it.mangaId }
	}

	/** 后台统计页数：PDF 用 PdfRenderer 数页，其余暂不统计（缓存后不再重复）。 */
	private suspend fun statPages(indexed: List<LocalContentIndexEntity>) {
		val dispatcher = Dispatchers.IO.limitedParallelism(4)
		val changed = java.util.concurrent.atomic.AtomicBoolean(false)
		coroutineScope {
			for (entry in indexed) {
				if (pagesCache.containsKey(entry.mangaId)) continue
				launch(dispatcher) {
					val pages = runCatching {
						val file = java.io.File(entry.path)
						when {
							!file.isFile -> null
							file.name.endsWith(".pdf", true) ->
								org.skepsun.kototoro.local.pdf.LocalPdfParser(file).pageCount()
							else -> null
						}
					}.getOrNull()
					if (pages != null && pages > 0) {
						pagesCache[entry.mangaId] = pages
						changed.set(true)
					}
				}
			}
		}
		if (changed.get()) {
			localStorageChanges.emit(null)
		}
	}

	private fun buildOrderCacheKey(order: SortOrder?, filter: ContentListFilter?): String {
		return "$order|$pagesSortMode|${filter?.query}|${filter?.tags.orEmpty().joinToString(",") { it.title }}" +
			"|${filter?.types}|${filter?.tagsExclude.orEmpty().joinToString(",") { it.title }}|${filter?.contentRating}|${settings.isNsfwContentDisabled}"
	}

	/** 并行 stat：返回 path → 创建时间（顺带过滤已删除文件）。 */
	private suspend fun statCreationTimes(paths: List<String>): Map<String, Long> {
		if (paths.isEmpty()) return emptyMap()
		val result = ConcurrentHashMap<String, Long>()
		val dispatcher = Dispatchers.IO.limitedParallelism(8)
		coroutineScope {
			for (path in paths) {
				launch(dispatcher) {
					runCatching {
						val file = java.io.File(path)
						if (file.exists()) result[path] = file.creationTime
					}
				}
			}
		}
		return result
	}

	private fun org.skepsun.kototoro.core.db.dao.MangaDao.MangaLightRow.matchesQuery(query: String): Boolean {
		return title.contains(query, ignoreCase = true) ||
			altTitles?.contains(query, ignoreCase = true) == true ||
			authors?.contains(query, ignoreCase = true) == true
	}

	/** 全量本地内容（批量删除等场景使用，不受列表分页影响）。 */
	suspend fun getAllLocalContent(): List<Content> = getRawListAsFlow().toCollection(ArrayList()).unwrap()

	override suspend fun getDetails(manga: Content): Content = when {
		!manga.isLocal -> {
			// For saved manga, always re-parse from disk to get fresh chapter data
			// This ensures we get updated chapters after EPUB download/extraction
			// Bypass localContentIndex cache by using LocalContentParser.find directly
			val parser = LocalContentParser.find(storageManager.getAllReadableDirs(), manga)
			if (parser != null) {
				// Parse directly from disk to get fresh data
				parser.getContent(withDetails = true).manga
			} else {
				throw IllegalArgumentException("Content is not local or saved")
			}
		}

		else -> {
			// PDF 漫画的 url 是百分号编码的 pdf://<path>，不能直接 toUri() 再 File(uri.schemeSpecificPart)，
			// 否则 %20/%5B 等特殊字符不会解码，导致文件找不到、章节为空 → 详情页显示"该章缺失"。
			// 用字符串级提取路径 + URLDecoder.decode 还原真实文件路径。
			val rawUrl = manga.url
			val isPdf = rawUrl.isPdfUriString() ||
				rawUrl.startsWith("pdf:", ignoreCase = true) ||
				(rawUrl.endsWith(".pdf", ignoreCase = true) && rawUrl.contains("pdf:/", ignoreCase = true))
			if (isPdf) {
				val decodedPath = rawUrl.extractPdfPath()?.let {
					runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it)
				}
				val pdfFile = decodedPath?.let { java.io.File(it) }
				if (pdfFile != null && pdfFile.isFile && pdfFile.canRead()) {
					LocalContentParser(pdfFile).getContent(withDetails = true).manga
				} else {
					// 回退：尝试用 uri 方式解析
					LocalContentParser(manga.url.toUri()).getContent(withDetails = true).manga
				}
			} else {
				LocalContentParser(manga.url.toUri()).getContent(withDetails = true).manga
			}
		}
	}

	override suspend fun getPages(chapter: ContentChapter, nextChapterUrl: String?): List<ContentPage> {
		if (!chapter.source.isLocal) {
			android.util.Log.d("LocalMangaRepository", "Delegating getPages to original source: ${chapter.source.name}")
			return repositoryFactory.get().create(chapter.source).getPages(chapter, nextChapterUrl)
		}

		// 【V6 终极修复】加上 URL 百分号解码！
		// Uri.Builder.path() 会把路径里的特殊字符（日文、空格等）编码成 %XX，
		// 提取路径后必须用 URLDecoder.decode 解码回来，否则 File.exists() 永远返回 false。
		// 【V7】同时支持 pdf:// 和 pdf:/ 前缀（Uri.Builder 可能只生成一个斜杠）
		val url: String = chapter.url
		val pdfPrefix = "pdf://"
		val pdfPrefixSingle = "pdf:/"
		val isPdf = url.startsWith(pdfPrefix, ignoreCase = true) ||
			url.startsWith(pdfPrefixSingle, ignoreCase = true) ||
			(url.endsWith(".pdf", ignoreCase = true) && url.contains("pdf:/", ignoreCase = true))

		android.util.Log.d("LocalMangaRepository", "V6 getPages: url=$url isPdf=$isPdf title=${chapter.title}")

		if (isPdf) {
			// 手动提取路径：去掉 pdf:// 或 pdf:/ 前缀，去掉 # 之后的 fragment，再去多余的 '/'
			val withoutFrag = url.substringBefore('#')
			val withoutScheme = when {
				withoutFrag.startsWith("pdf://", ignoreCase = true) -> withoutFrag.substring(6)
				withoutFrag.startsWith("pdf:/", ignoreCase = true) -> withoutFrag.substring(5)
				withoutFrag.startsWith("pdf:", ignoreCase = true) -> withoutFrag.substring(4)
				else -> null
			}
			val trimmed = withoutScheme?.trimStart('/')
			val rawPath = if (trimmed.isNullOrEmpty()) null else "/$trimmed"
			if (rawPath == null) {
				android.util.Log.e("LocalMangaRepository", "V6 PDF: cannot extract raw path from url=$url")
				return emptyList()
			}
			// 【关键】URL 百分号解码！
			val pdfPath = runCatching {
				java.net.URLDecoder.decode(rawPath, "UTF-8")
			}.getOrDefault(rawPath)
			val pdfFile = java.io.File(pdfPath)
			android.util.Log.d("LocalMangaRepository", "V6 PDF: rawPath=$rawPath decodedPath=$pdfPath exists=${pdfFile.exists()} readable=${pdfFile.canRead()}")
			if (!pdfFile.exists() || !pdfFile.canRead()) {
				android.util.Log.e("LocalMangaRepository", "V6 PDF: file not found or not readable: $pdfPath")
				return emptyList()
			}
			val parser = org.skepsun.kototoro.local.pdf.LocalPdfParser(pdfFile)
			val pageCount = parser.pageCount()
			android.util.Log.d("LocalMangaRepository", "V6 PDF: pageCount=$pageCount")
			if (pageCount <= 0) return emptyList()
			// 【V7】不用 Uri.Builder（它会把 pdf:/path 生成成 pdf:/path 只有一个斜杠），
			// 改用字符串拼接 + Uri.encode 编码路径中的特殊字符
			val encodedPath = android.net.Uri.encode(pdfPath, "/")
			return (0 until pageCount).map { i ->
				val pageUrl = "pdf://$encodedPath#page/$i"
				ContentPage(
					id = "$pdfPath#$i".hashCode().toLong().let { if (it < 0) -it else it },
					url = pageUrl,
					preview = null,
					source = LocalMangaSource,
				)
			}
		}

		val chapterUri = runCatching { url.toUri() }.getOrNull()
		val scheme = chapterUri?.scheme

		// NEW ARCHITECTURE: EPUB chapters use epub:// protocol
		if (scheme == "epub" || scheme == "localepub") {
			android.util.Log.d("LocalMangaRepository", "EPUB chapter detected (new architecture / localepub)")
			return listOf(
				ContentPage(
					id = 0,
					url = chapter.url,
					preview = null,
					source = LocalMangaSource,
				)
			)
		}

		// Legacy EPUB chapters with file://path#chapter/N format are no longer supported
		if (scheme == "file" && chapterUri?.fragment?.contains("chapter/") == true) {
			android.util.Log.w("LocalMangaRepository", "Legacy EPUB chapter format detected: ${chapter.url}")
			android.util.Log.w("LocalMangaRepository", "Please re-download this manga to use the new EPUB architecture")
			return emptyList()
		}

		// 普通章节，使用LocalContentParser
		android.util.Log.d("LocalMangaRepository", "V5: fallback to LocalContentParser (scheme=$scheme")
		return LocalContentParser(requireNotNull(chapterUri) { "Invalid chapter url: ${chapter.url}" }).getPages(chapter)
	}

	override suspend fun getChapterContent(chapter: ContentChapter, nextChapterUrl: String?): NovelChapterContent? {
		if (!chapter.source.isLocal) {
			android.util.Log.d("LocalMangaRepository", "Delegating getChapterContent to original source: ${chapter.source.name}")
			return repositoryFactory.get().create(chapter.source).getChapterContent(chapter, nextChapterUrl)
		}
		return super.getChapterContent(chapter, nextChapterUrl)
	}

	suspend fun delete(manga: Content): Boolean {
		val file = if (manga.isLocal) {
			val uri = manga.url.toUri()
			if (uri.scheme == "file") {
				File(requireNotNull(uri.path) { "File uri path is null: $uri" })
			} else {
				File(uri.schemeSpecificPart)
			}
		} else {
			findSavedContent(manga, withDetails = false)?.file ?: return false
		}
		val result = file.deleteAwait()
		if (result) {
			localContentIndex.delete(manga.id)
			localStorageChanges.emit(null)
		}
		return result
	}

	suspend fun deleteChapters(manga: Content, ids: Set<Long>) = lock.withLock(manga) {
		val subject = if (manga.isLocal) {
			org.skepsun.kototoro.local.domain.model.LocalContent(manga)
		} else {
			checkNotNull(findSavedContent(manga, withDetails = false)) {
				"Content is not stored on local storage"
			}
		}
		LocalContentUtil(subject.manga, subject.file).deleteChapters(ids)
		val updated = LocalContentParser(subject.file).getContent(withDetails = true)
		localStorageChanges.emit(updated)
	}

	suspend fun getRemoteContent(localContent: Content): Content? {
		return runCatchingCancellable {
			LocalContentParser(localContent.url.toUri()).getContentInfo()?.takeUnless { it.isLocal }
		}.onFailure {
			it.printStackTraceDebug()
		}.getOrNull()
	}

	suspend fun findSavedContent(remoteContent: Content, withDetails: Boolean = true): LocalContent? = runCatchingCancellable {
		// very fast path
		localContentIndex.get(remoteContent.id, withDetails)?.let { cached ->
			return@runCatchingCancellable cached
		}
		// fast path
		LocalContentParser.find(storageManager.getAllReadableDirs(), remoteContent)?.let {
			return it.getContent(withDetails)
		}
		// slow path
		val files = getAllFiles()
		return channelFlow {
			for (file in files) {
				launch {
					val mangaInput = LocalContentParser.getOrNull(file)
					runCatchingCancellable {
						val mangaInfo = mangaInput?.getContentInfo()
						if (mangaInfo != null && mangaInfo.id == remoteContent.id) {
							send(mangaInput)
						}
					}.onFailure {
						it.printStackTraceDebug()
					}
				}
			}
		}.firstOrNull()?.getContent(withDetails)
	}.onSuccess { x: LocalContent? ->
		if (x != null) {
			localContentIndex.put(x)
		}
	}.onFailure {
		it.printStackTraceDebug()
	}.getOrNull()

	override suspend fun getPageUrl(page: ContentPage) = page.url

	override suspend fun getRelated(seed: Content): List<Content> = emptyList()

	suspend fun getOutputDir(manga: Content, fallback: File?): File? {
		val isVideo = manga.source?.getContentType() == ContentType.VIDEO
		val isNovel = manga.source?.getContentType() == ContentType.NOVEL

		val defaultDir = fallback?.takeIfWriteable() ?: when {
			isVideo -> storageManager.getVideoRoot()?.takeIfWriteable()
			isNovel -> storageManager.getDefaultNovelWriteableDir()
			else -> storageManager.getDefaultWriteableDir()
		}
		
		if (defaultDir != null && LocalContentOutput.get(defaultDir, manga) != null) {
			return defaultDir
		}
		
		val writeableDirs = when {
			isVideo -> storageManager.getVideoWriteableDirs()
			isNovel -> storageManager.getNovelWriteableDirs()
			else -> storageManager.getWriteableDirs()
		}
		
		return writeableDirs
			.firstOrNull {
				LocalContentOutput.get(it, manga) != null
			} ?: defaultDir
	}

	suspend fun cleanup(): Boolean {
		if (lock.isNotEmpty()) {
			return false
		}
		val dirs = storageManager.getAllWriteableDirs()
		runInterruptible(Dispatchers.IO) {
			val filter = TempFileFilter()
			dirs.forEach { dir ->
				dir.withChildren { children ->
					children.forEach { child ->
						if (filter.accept(child)) {
							child.deleteRecursively()
						}
					}
				}
			}
		}
		return true
	}

	// 本地列表解析缓存：path -> (lastModified, content)
	// 缓存按文件修改时间失效，只有文件真正变化时才重新解析。
	private val contentCache = ConcurrentHashMap<String, Pair<Long, LocalContent>>()

	// 后台增量刷新用作用域与互斥锁（进程级单例，生命周期与进程一致）
	private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private val refreshMutex = Mutex()

	init {
		// 文件变更（下载完成、删除等）时自动增量刷新索引；
		// 冷启动打开列表不再自动重新扫描，改为手动刷新按钮触发。
		backgroundScope.launch {
			localStorageChanges.collect {
				refreshIndexNow()
			}
		}
	}

	/**
	 * 快通道：只读持久化索引返回本地内容（缓存优先）。
	 * 冷启动/重新打开列表时不做磁盘重扫，文件是否过期由
	 * [refreshIndexNow] 在文件变更事件或手动刷新时处理。
	 */
	fun getRawListAsFlow(): Flow<LocalContent> = channelFlow {
		val t0 = System.currentTimeMillis()
		val indexed = localContentIndex.getAllIndexed()
		android.util.Log.d("LocalListTiming", "getAllIndexed: ${System.currentTimeMillis() - t0}ms count=${indexed.size}")
		// 一次批量读取全部 manga 行，替代逐本查询（N 次 SQL → 1 次）
		val t1 = System.currentTimeMillis()
		val cachedById = mangaDataRepository.findContentByIds(indexed.map { it.mangaId })
		android.util.Log.d("LocalListTiming", "findContentByIds: ${System.currentTimeMillis() - t1}ms")
		var existsMs = 0L
		for (entry in indexed) {
			val file = File(entry.path)
			// 已删除的文件交给刷新清理，这里不阻塞、不解析
			val te = System.currentTimeMillis()
			if (!file.exists()) continue
			existsMs += System.currentTimeMillis() - te
			val cached = contentCache[entry.path]
			val local = when {
				cached != null -> cached.second
				else -> cachedById[entry.mangaId]?.let { LocalContent(it, file) }
			}
			if (local != null) {
				contentCache[entry.path] = entry.mtime to local
				send(local)
			}
		}
		android.util.Log.d("LocalListTiming", "getRawListAsFlow done: ${System.currentTimeMillis() - t0}ms (exists=${existsMs}ms)")
	}

	/** 手动刷新时统计页数（其他情况不统计，避免每次打开列表都扫描 PDF）。 */
	suspend fun statPagesNow() {
		val indexed = localContentIndex.getAllIndexed()
		if (indexed.isEmpty()) return
		statPages(indexed)
	}

	/**
	 * 全量扫描：遍历所有可读目录并解析每个文件（仅在首次安装/升级重建索引时使用，
	 * 见 [org.skepsun.kototoro.local.data.index.LocalContentIndex.update]）。
	 */
	fun getRawListFullScanFlow(): Flow<LocalContent> = channelFlow {
		val files = getAllFiles()
		val dispatcher = Dispatchers.IO.limitedParallelism(MAX_PARALLELISM)
		val currentPaths = HashSet<String>()
		for (file in files) {
			currentPaths.add(file.absolutePath)
			launch(dispatcher) {
				val cached = contentCache[file.absolutePath]
				val lastModified = file.lastModified()
				if (cached != null && cached.first == lastModified) {
					send(cached.second)
					return@launch
				}
				runCatchingCancellable {
					LocalContentParser.getOrNull(file)?.getContent(withDetails = false)
				}.onFailure { e ->
					e.printStackTraceDebug()
				}.onSuccess { m ->
					if (m != null) {
						contentCache[file.absolutePath] = lastModified to m
						send(m)
					}
				}
			}
		}
		// 清理已不存在文件的缓存（避免无限增长）
		contentCache.keys.retainAll(currentPaths)
	}

	private suspend fun getRawList(): ArrayList<LocalContent> = getRawListAsFlow().toCollection(ArrayList())

	/**
	 * 增量刷新索引：只解析新增/变更文件，并删除已不存在文件的索引项。
	 * 通过目录签名门控：目录未变化时直接跳过（避免重复扫描与死循环），
	 * 目录变化时才实际扫描。由文件变更事件与手动"刷新"按钮触发。
	 */
	suspend fun refreshIndexNow() {
		if (refreshMutex.isLocked) return
		refreshMutex.withLock {
			if (!isStorageSignatureChanged()) return
			refreshIndex()
			// 索引变化后失效有序缓存，确保下次 getList 重建（新文件立即可见）
			orderedIdsCache = null
			orderedCacheKey = null
		}
	}

	private suspend fun refreshIndex() {
		var changed = false
		runCatchingCancellable {
			val indexedByPath = localContentIndex.getAllIndexed().associateBy { it.path }
			val seenPaths = HashSet<String>()
			val files = getAllFiles()
			for (file in files) {
				val path = file.absolutePath
				seenPaths.add(path)
				val entry = indexedByPath[path]
				val lastModified = file.lastModified()
				if (entry != null && entry.mtime == lastModified) continue
				val parsed = runCatchingCancellable {
					LocalContentParser.getOrNull(file)?.getContent(withDetails = false)
				}.getOrNull() ?: continue
				localContentIndex.put(parsed)
				contentCache[path] = lastModified to parsed
				changed = true
			}
			// 删除磁盘上已不存在的索引项
			for (path in indexedByPath.keys) {
				if (path in seenPaths) continue
				indexedByPath[path]?.let { localContentIndex.delete(it.mangaId) }
				contentCache.remove(path)
				changed = true
			}
		}.onFailure {
			it.printStackTraceDebug()
		}
		persistStorageSignature()
		if (changed) {
			localStorageChanges.emit(null)
		}
	}

	private suspend fun isStorageSignatureChanged(): Boolean {
		return buildStorageSignature() != scanSignaturePrefs.getString(KEY_SCAN_SIGNATURE, null)
	}

	private suspend fun buildStorageSignature(): String {
		val dirs = storageManager.getAllReadableDirs()
		return dirs.joinToString("|") { "${it.absolutePath}:${it.lastModified()}" }
	}

	private suspend fun persistStorageSignature() {
		scanSignaturePrefs.edit { putString(KEY_SCAN_SIGNATURE, buildStorageSignature()) }
	}

	private suspend fun getAllFiles() = storageManager.getAllReadableDirs()
		.asSequence()
		.flatMap { dir ->
			dir.withChildren { children -> 
				children.filterNot { it.isHidden || it.shouldSkip() }.toList()
			}
		}

	private fun Collection<LocalContent>.unwrap(): List<Content> = map { it.manga }

	private fun File.shouldSkip(): Boolean {
		return name.endsWith(BACKUP_SUFFIX, ignoreCase = true) ||
			(isDirectory && File(this, FILENAME_SKIP).exists())
	}
}
