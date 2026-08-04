package org.skepsun.kototoro.local.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.net.toUri
import org.skepsun.kototoro.core.model.LocalMangaSource
import org.skepsun.kototoro.core.util.ext.URI_SCHEME_PDF
import org.skepsun.kototoro.core.util.ext.extractPdfPath
import org.skepsun.kototoro.core.util.ext.isPdfUriString
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.util.longHashCode
import java.io.File

/**
 * 构建 PDF chapter/page URL。
 *
 * 【V7】不再使用 [Uri.Builder]（它会把 pdf:///path 生成成 pdf:/path 单斜杠格式），
 * 改用字符串拼接 + [Uri.encode]（保留 "/" 不编码）。
 */
private fun buildPdfUrl(path: String, fragment: String? = null): String {
	val encodedPath = Uri.encode(path, "/")
	return if (fragment != null) {
		"$URI_SCHEME_PDF://$encodedPath#$fragment"
	} else {
		"$URI_SCHEME_PDF://$encodedPath"
	}
}

/**
 * 本地 PDF 漫画解析器。基于 Android 原生 [PdfRenderer]（API 21+），无需第三方依赖。
 *
 * 支持两种入口：
 * 1. [pdfFile] 为 PDF 文件本身，[metaDir] 为同名元数据所在目录（可能含 cover.jpg / info.txt）
 * 2. [pdfFile] 与 [metaDir] 为同一目录（PDF 在文件夹中，封面/简介也在该文件夹）
 *
 * 生成的章节 URL 使用 `pdf://<absolutePath>` 协议，
 * 页面 URL 使用 `pdf://<absolutePath>#page/<index>` 协议，
 * 由 [org.skepsun.kototoro.reader.domain.PageLoader] 负责栅格化渲染。
 */
class LocalPdfParser(
	private val pdfFile: File,
	private val metaDir: File = pdfFile.parentFile ?: pdfFile.absoluteFile.parentFile ?: pdfFile,
) {

	/**
	 * 解析 PDF 为 [Content]。会读取同目录下的 `info.txt`（标题/标签）和 `cover.jpg`（封面）。
	 *
	 * 封面优先级：
	 * 1. 同目录下的 `cover.jpg` / `cover.png` / 其他图片文件
	 * 2. 渲染 PDF 第一页作为示意图（在调用方需要时调用 [renderPage] 动态生成）
	 *
	 * 搜索增强：
	 * 为了让名字 / ID / 标签 / 文件夹名等都能被搜索到，把所有索引文本拼到 [Content.altTitles] 中。
	 * 因为 [MangaDao.searchByTitle] 只匹配 `title` 与 `alt_title`（逗号分隔字符串）。
	 *
	 * @param withDetails 是否填充章节列表
	 */
	suspend fun parseContent(withDetails: Boolean): Content {
		val pageCount = pageCount()
		val meta = readInfoTxt()
		val title = meta?.title ?: pdfFile.nameWithoutExtension
		val coverUrl = findCover()?.toUri()?.toString()

		// 作者集合
		val authorSet = meta?.authors.orEmpty()

		// 合成所有可搜索文本（塞进 altTitles，让 MangaDao.searchByTitle 能统一搜索）
		val searchableTexts = buildSet {
			// 文件夹名
			add(metaDir.name)
			// PDF 文件名（不含扩展名）
			add(pdfFile.nameWithoutExtension)
			// PDF 全名
			add(pdfFile.name)
			// 绝对路径的哈希（即 Content.id，作为 ID 标识）
			add(pdfFile.absolutePath.longHashCode().toString())
			// info.txt 中解析到的所有标签名
			meta?.tags?.forEach { add(it.title) }
			// 作者/社团名
			authorSet.forEach { add(it) }
			// 描述中每个关键字（取前 500 字符，避免过大）
			val descText = meta?.description
			if (!descText.isNullOrBlank()) {
				// 保留原始整段摘要（空格分词后拼回，避免 LIKE 匹配不到整体）
				val descFlat = descText.replace('\n', ' ').replace('\r', ' ')
				if (descFlat.length > 500) {
					add(descFlat.substring(0, 500))
				} else {
					add(descFlat)
				}
			}
			// info.txt 原始键值对里的其他行（如语言、页数、大小等）
			meta?.extraFields?.forEach { (k, v) ->
				add("$k $v")
			}
		}.filter { it.isNotBlank() && it != title } // 去掉空白和标题重复

		val pdfPath = pdfFile.absolutePath
		val chapterUrl = buildPdfUrl(pdfPath)
		val chapter = ContentChapter(
			id = "${pdfPath}:0".longHashCode(),
			title = title,
			number = 1f,
			volume = 0,
			url = chapterUrl,
			scanlator = null,
			uploadDate = pdfFile.lastModified(),
			branch = null,
			source = LocalMangaSource,
		)
		return Content(
			id = pdfFile.absolutePath.longHashCode(),
			title = title,
			altTitles = searchableTexts.toSet(),
			url = chapterUrl,
			publicUrl = pdfFile.toUri().toString(),
			rating = -1f,
			contentRating = null,
			coverUrl = coverUrl,
			tags = meta?.tags.orEmpty(),
			state = null,
			authors = authorSet,
			largeCoverUrl = null,
			description = meta?.description,
			chapters = if (withDetails) listOf(chapter) else null,
			source = LocalMangaSource,
		)
	}

	/** 返回 PDF 页数。文件不可读或非 PDF 时返回 0。 */
	fun pageCount(): Int {
		if (!pdfFile.isFile || !pdfFile.canRead()) {
			android.util.Log.w("LocalPdfParser", "pageCount: file not readable: $pdfFile")
			return 0
		}
		return runCatching {
			openRenderer().use { it.pageCount }
		}.onFailure {
			android.util.Log.e("LocalPdfParser", "pageCount failed for $pdfFile", it)
		}.getOrDefault(0)
	}

	/**
	 * 渲染指定页为 [Bitmap]，供 [PageLoader] 调用。
	 * @param pageIndex 从 0 开始的页码
	 * @param targetWidth 目标宽度（像素），按比例缩放，0 表示按原始尺寸
	 */
	fun renderPage(pageIndex: Int, targetWidth: Int = 1080): Bitmap? {
		if (!pdfFile.isFile || !pdfFile.canRead()) {
			android.util.Log.w("LocalPdfParser", "renderPage: file not readable: $pdfFile")
			return null
		}
		return runCatching {
			openRenderer().use { renderer ->
				if (pageIndex < 0 || pageIndex >= renderer.pageCount) {
					android.util.Log.w("LocalPdfParser", "renderPage: pageIndex $pageIndex out of range [0, ${renderer.pageCount})")
					return@use null
				}
				renderer.openPage(pageIndex).use { page ->
					val width = if (targetWidth > 0) targetWidth else page.width
					val scale = width.toFloat() / page.width.toFloat()
					val height = (page.height * scale).toInt().coerceAtLeast(1)
					val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
					bitmap.eraseColor(Color.WHITE)
					page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
					bitmap
				}
			}
		}.onFailure {
			android.util.Log.e("LocalPdfParser", "renderPage failed: page=$pageIndex, file=$pdfFile", it)
		}.getOrNull()
	}

	private fun openRenderer(): PdfRenderer {
		val pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
		return PdfRenderer(pfd)
	}

	private fun findCover(): File? {
		if (!metaDir.isDirectory) return null
		// 1) 优先同名封面文件（cover.jpg / cover.png 等）
		val explicitCover = arrayOf("cover.jpg", "cover.jpeg", "cover.png", "cover.webp", "cover.bmp")
			.map { File(metaDir, it) }
			.firstOrNull { it.isFile }
		if (explicitCover != null) return explicitCover
		// 2) 同目录下任意其他图片（非 PDF）
		val anyPic = metaDir.listFiles()
			?.firstOrNull { it.isFile && it.extension.lowercase() in COVER_EXTENSIONS }
		if (anyPic != null) return anyPic
		// 3) 没有封面图：把 PDF 第一页渲染为示意图，缓存到 app 外部缓存目录
		return runCatching { generateCoverFromPdf() }.getOrNull()
	}

	/**
	 * 将 PDF 第一页栅格化后写入应用缓存，作为示意图封面。
	 *
	 * 缓存目录：`Context.getExternalCacheDir()/pdf_covers/`（优先，卸载时自动清理），
	 * 不存在则回退到 `Context.getCacheDir()/pdf_covers/`，再不行就回退到 PDF 同目录 `.cover.jpg`。
	 * 缓存命中（存在、大小 > 0、修改时间 >= PDF 修改时间）时直接复用。
	 */
	private fun generateCoverFromPdf(): File? {
		val appCtx = try {
			Class.forName("android.app.ActivityThread")
				.getMethod("currentApplication")
				.invoke(null) as? android.content.Context
		} catch (_: Throwable) {
			null
		}
		val cacheRoot: File? =
			appCtx?.externalCacheDir ?: appCtx?.cacheDir
		val cacheDir = if (cacheRoot != null) {
			File(cacheRoot, "pdf_covers").apply { mkdirs() }
		} else {
			// 实在取不到 Context，就放到 PDF 同目录下，命名为隐藏文件，避免污染目录
			File(metaDir, ".cover.jpg")
		}
		val coverFile = if (cacheDir.isDirectory) {
			// 用 PDF 绝对路径的 hash 做文件名，避免重名
			File(cacheDir, "${pdfFile.absolutePath.longHashCode().toString(16)}.jpg")
		} else {
			cacheDir // 上面 fallback 到 metaDir/.cover.jpg 的情况，本身就是一个文件路径
		}
		// 缓存命中：文件存在且非空且不比 PDF 旧
		if (coverFile.isFile && coverFile.length() > 0 && coverFile.lastModified() >= pdfFile.lastModified()) {
			return coverFile
		}
		// 渲染 PDF 第 0 页（以列表封面的宽度：~600px 足够，又不至于太大）
		val bitmap = renderPage(0, targetWidth = 600) ?: return null
		return runCatching {
			val fos = java.io.FileOutputStream(coverFile)
			fos.use {
				bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it)
			}
			coverFile.setLastModified(pdfFile.lastModified()) // 与 PDF 文件时间绑定
			if (coverFile.isFile && coverFile.length() > 0) coverFile else null
		}.getOrNull()
	}

	/** 解析同目录 info.txt，提取标题、标签、描述。格式：`键: 值` 每行一个。 */
	private fun readInfoTxt(): PdfMeta? {
		val infoFile = File(metaDir, "info.txt")
		if (!infoFile.isFile) return null
		val text = runCatching { infoFile.readText() }.getOrNull() ?: return null
		val map = LinkedHashMap<String, String>()
		text.lineSequence().forEach { line ->
			val idx = line.indexOf(':')
			if (idx > 0) {
				val key = line.substring(0, idx).trim()
				val value = line.substring(idx + 1).trim()
				if (key.isNotEmpty() && value.isNotEmpty()) {
					map[key] = value
				}
			}
		}
		val title = map.remove("标题") ?: map.remove("title") ?: map.remove("Title")
		val tagsRaw = map.remove("标签") ?: map.remove("tags") ?: map.remove("Tags")
		val tags = tagsRaw
			?.split(',', '，', '/')
			?.map { it.trim() }
			?.filter { it.isNotEmpty() }
			?.mapTo(linkedSetOf()) { ContentTag(title = it, key = it.lowercase(), source = LocalMangaSource) }
			.orEmpty()
		// 作者 / 社团等信息单独提取
		val authorRaw = map.remove("作者") ?: map.remove("author") ?: map.remove("Author") ?: map.remove("社团") ?: map.remove("circle") ?: map.remove("Circle")
		val authors = authorRaw
			?.split(',', '，', '/')
			?.map { it.trim() }
			?.filter { it.isNotEmpty() }
			.orEmpty()
		return PdfMeta(
			title = title,
			tags = tags,
			authors = authors.toSet(),
			description = text,
			extraFields = map,
		)
	}

	private data class PdfMeta(
		val title: String?,
		val tags: Set<ContentTag>,
		val authors: Set<String>,
		val description: String?,
		val extraFields: Map<String, String>,
	)

	companion object {
		private val COVER_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "bmp")

		/**
		 * 从 `pdf://<path>#page/<index>` URI 解析出 PDF 文件和页码。
		 * 关键：必须对提取出的路径做 URL 百分号解码（URLDecoder.decode），
		 * 因为 Uri.Builder.path() 会把特殊字符编码为 %XX。
		 */
		fun parsePageUri(uri: Uri): Pair<File, Int>? {
			val raw = uri.toString()
			// 【V7】同时支持 pdf:// 和 pdf:/ 前缀
			// （Uri.Builder 生成的可能只有一个斜杠 pdf:/path）
			val isPdf = uri.scheme == URI_SCHEME_PDF ||
				raw.startsWith("pdf://", ignoreCase = true) ||
				raw.startsWith("pdf:/", ignoreCase = true) ||
				(raw.endsWith(".pdf", ignoreCase = true) && raw.contains("pdf:/", ignoreCase = true))
			if (!isPdf) return null

			// 1) 从原始字符串提取路径（手写字符串操作，不依赖 Uri.path）
			//    兼容 pdf://path、pdf:/path、pdf:///path
			val withoutFrag = raw.substringBefore('#')
			val withoutScheme = when {
				withoutFrag.startsWith("pdf://", ignoreCase = true) -> withoutFrag.substring(6)
				withoutFrag.startsWith("pdf:/", ignoreCase = true) -> withoutFrag.substring(5)
				withoutFrag.startsWith("pdf:", ignoreCase = true) -> withoutFrag.substring(4)
				else -> null
			}
			val trimmed = withoutScheme?.trimStart('/')
			val rawPath = if (trimmed.isNullOrEmpty()) null else "/$trimmed"
			if (rawPath == null) {
				android.util.Log.e("LocalPdfParser", "parsePageUri: cannot extract raw path from $raw")
				return null
			}
			// 【关键】URL 百分号解码
			val decodedPath = runCatching {
				java.net.URLDecoder.decode(rawPath, "UTF-8")
			}.getOrDefault(rawPath)

			// 2) 提取页码 fragment
			val hashIdx = raw.lastIndexOf('#')
			val fragment = if (hashIdx >= 0) raw.substring(hashIdx + 1) else uri.fragment
			val pageIndex = fragment
				?.removePrefix("page/")
				?.toIntOrNull()
				?: return null

			android.util.Log.d("LocalPdfParser", "parsePageUri: rawPath=$rawPath decodedPath=$decodedPath page=$pageIndex")
			return File(decodedPath) to pageIndex
		}
	}
}
