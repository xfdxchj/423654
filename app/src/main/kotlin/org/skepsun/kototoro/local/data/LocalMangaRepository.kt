package org.skepsun.kototoro.local.data

import androidx.core.net.toFile
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toCollection
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import org.skepsun.kototoro.core.model.LocalMangaSource
import org.skepsun.kototoro.core.model.isLocal
import org.skepsun.kototoro.core.model.isNsfw
import org.skepsun.kototoro.core.model.getContentType
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.parsers.model.NovelChapterContent
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.util.AlphanumComparator
import org.skepsun.kototoro.core.util.ext.URI_SCHEME_PDF
import org.skepsun.kototoro.core.util.ext.extractPdfPath
import org.skepsun.kototoro.core.util.ext.isPdfUriString
import org.skepsun.kototoro.core.util.ext.deleteAwait
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
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

private const val MAX_PARALLELISM = 4
private const val FILENAME_SKIP = ".notamanga"
private const val BACKUP_SUFFIX = ".bk"

@Singleton
class LocalMangaRepository @Inject constructor(
	private val storageManager: LocalStorageManager,
	private val localContentIndex: LocalContentIndex,
	@LocalStorageChanges private val localStorageChanges: MutableSharedFlow<LocalContent?>,
	private val settings: AppSettings,
	private val lock: ContentLock,
	private val repositoryFactory: Provider<ContentRepository.Factory>,
) : ContentRepository {

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

	override suspend fun getList(offset: Int, order: SortOrder?, filter: ContentListFilter?): List<Content> {
		if (offset > 0) {
			return emptyList()
		}
		val list = getRawList()
		if (settings.isNsfwContentDisabled) {
			list.removeAll { it.manga.isNsfw() }
		}
		if (filter != null) {
			val query = filter.query
			if (!query.isNullOrEmpty()) {
				list.retainAll { x -> x.isMatchesQuery(query) }
			}
			if (filter.tags.isNotEmpty()) {
				list.retainAll { x -> x.containsTags(filter.tags.mapToSet { it.title }) }
			}
			if (filter.types.isNotEmpty()) {
				list.retainAll { x -> (x.manga.source?.contentType ?: ContentType.MANGA) in filter.types }
			}
			if (filter.tagsExclude.isNotEmpty()) {
				list.removeAll { x -> x.containsAnyTag(filter.tagsExclude.mapToSet { it.title }) }
			}
			filter.contentRating.singleOrNull()?.let { contentRating ->
				val isNsfw = contentRating == ContentRating.ADULT
				list.retainAll { it.manga.isNsfw() == isNsfw }
			}
			if (!query.isNullOrEmpty() && order == SortOrder.RELEVANCE) {
				list.sortBy { it.manga.title.levenshteinDistance(query) }
			}
		}
		when (order) {
			SortOrder.ALPHABETICAL -> list.sortWith(compareBy(AlphanumComparator()) { x -> x.manga.title })
			SortOrder.RATING -> list.sortByDescending { it.manga.rating }
			SortOrder.NEWEST,
			SortOrder.UPDATED -> list.sortWith(compareBy({ -it.createdAt }, { it.manga.id }))

			else -> Unit
		}
		return list.unwrap()
	}

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

		else -> LocalContentParser(manga.url.toUri()).getContent(withDetails = true).manga
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

	fun getRawListAsFlow(): Flow<LocalContent> = channelFlow {
		val files = getAllFiles()
		val dispatcher = Dispatchers.IO.limitedParallelism(MAX_PARALLELISM)
		for (file in files) {
			launch(dispatcher) {
				runCatchingCancellable {
					LocalContentParser.getOrNull(file)?.getContent(withDetails = false)
				}.onFailure { e ->
					e.printStackTraceDebug()
				}.onSuccess { m ->
					if (m != null) send(m)
				}
			}
		}
	}

	private suspend fun getRawList(): ArrayList<LocalContent> = getRawListAsFlow().toCollection(ArrayList())

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
