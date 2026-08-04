package org.skepsun.kototoro.core.util.ext

import android.net.Uri
import androidx.core.net.toUri
import okio.Path
import java.io.File

const val URI_SCHEME_ZIP = "file+zip"
const val URI_SCHEME_PDF = "pdf"
private const val URI_SCHEME_FILE = "file"
private const val URI_SCHEME_HTTP = "http"
private const val URI_SCHEME_HTTPS = "https"
private const val URI_SCHEME_LEGACY_CBZ = "cbz"
private const val URI_SCHEME_LEGACY_ZIP = "zip"

private const val PDF_PREFIX = "$URI_SCHEME_PDF://"
private const val PDF_EXT = ".pdf"

fun Uri.isZipUri() = scheme.let {
	it == URI_SCHEME_ZIP || it == URI_SCHEME_LEGACY_CBZ || it == URI_SCHEME_LEGACY_ZIP
}

fun Uri.isFileUri() = scheme == URI_SCHEME_FILE

/**
 * 鲁棒判断是否是 PDF URI。
 *
 * Android 的 [Uri.parse] 遇到未编码的保留字符（如路径中的 [、]、空格）时，
 * 可能把 [Uri.scheme] 解析为 null（因为 `[` 是 IPv6 保留字符）。
 * 因此这里必须**同时检查** `scheme` 与原始字符串前缀。
 */
fun Uri.isPdfUri(): Boolean {
	val s = scheme
	if (s == URI_SCHEME_PDF) return true
	// 回退：检查 toString() 是否以 pdf:// 开头；或者 isHierarchical + 路径/整个 URI 以 .pdf 结尾
	val raw = toString()
	if (raw.startsWith(PDF_PREFIX, ignoreCase = true)) return true
	if (raw.endsWith(PDF_EXT, ignoreCase = true) &&
		(raw.contains(PDF_PREFIX, ignoreCase = true) || (path?.endsWith(PDF_EXT, ignoreCase = true) == true))
	) {
		return true
	}
	return false
}

/**
 * 对 [String] 的鲁棒 PDF URI 判断。
 */
fun String.isPdfUriString(): Boolean =
	startsWith(PDF_PREFIX, ignoreCase = true) ||
		(endsWith(PDF_EXT, ignoreCase = true) && contains(PDF_PREFIX, ignoreCase = true))

/**
 * 从 PDF URI（无论是编码的、未编码的、scheme 解析失败的）中提取出本地文件绝对路径。
 *
 * 兼容：
 * - `pdf:///abs/path/to/file.pdf` （scheme 正常）
 * - `pdf:///abs/path/(C103) [x]/file.pdf` （[ ] 导致 scheme 被 Uri.parse 解析为 null）
 * - `pdf:///abs/path/file.pdf#page/3` （带 fragment）
 */
fun String.extractPdfPath(): String? {
	// 1) 去掉 pdf:// 前缀，以及 fragment（# 之后）
	val withoutFrag = substringBefore('#')
	val withoutScheme = when {
		withoutFrag.startsWith(PDF_PREFIX, ignoreCase = true) ->
			withoutFrag.substring(PDF_PREFIX.length)
		withoutFrag.startsWith(URI_SCHEME_PDF, ignoreCase = true) ->
			withoutFrag.substring(URI_SCHEME_PDF.length).trimStart(':')
		else -> return null
	}
	// 2) 如果去掉前缀后还有多余的 '/'，只保留单个开头 '/'
	//    例："pdf:///storage/..." → 去掉 prefix 后是 "//storage/..." → 保留一个 / 变成 "/storage/..."
	val trimmed = withoutScheme.trimStart('/')
	if (trimmed.isEmpty()) return null
	return "/$trimmed"
}

/**
 * 基于 [extractPdfPath] 的 [Uri] 版本，优先用 Uri.path，失败则回退到原始字符串。
 */
fun Uri.extractPdfPath(): String? {
	val p0 = path
	if (!p0.isNullOrEmpty() && p0.endsWith(PDF_EXT, ignoreCase = true)) {
		return p0
	}
	return toString().extractPdfPath()
}

fun Uri.isNetworkUri() = scheme.let {
	it == URI_SCHEME_HTTP || it == URI_SCHEME_HTTPS
}

fun File.toZipUri(entryPath: String): Uri = "$URI_SCHEME_ZIP://$absolutePath#$entryPath".toUri()

fun File.toZipUri(entryPath: Path?): Uri =
	toZipUri(entryPath?.toString()?.removePrefix(Path.DIRECTORY_SEPARATOR).orEmpty())

fun String.toUriOrNull() = if (isEmpty()) null else this.toUri()

fun File.toUri(fragment: String?): Uri = toUri().run {
	if (fragment != null) {
		buildUpon().fragment(fragment).build()
	} else {
		this
	}
}
