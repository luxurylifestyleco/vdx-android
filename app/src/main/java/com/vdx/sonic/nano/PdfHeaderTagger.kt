package com.vdx.sonic.nano

import android.content.Context
import android.os.Environment
import android.provider.MediaStore

/**
 * PdfHeaderTagger — the junk-document memory layer (the verbatim NanoCore ask).
 *
 * "Expose the header of the PDF" → tag it → make it searchable.
 * Runs on EVERY phone (no Nano needed): finds PDFs/docs via MediaStore, reads
 * the indexed columns (display name, size, date, relative path), and rule-tags
 * each document from its NAME + PATH signatures:
 *   boarding pass / e-ticket / receipt / invoice / menu / statement / report / document / other
 *
 * Header-TEXT extraction (true content reading) is a follow-up rung; it needs a
 * PDF text-layer library. The name/path/date rules below already cover the bulk
 * of the use case because issuers stamp strong filename signatures.
 *
 * Deletion is NOT a feature of this class by design: "clean junk" only ever
 * lists candidates for the user to confirm — the confirm-first law.
 */
/** classify()/junk logic are context-free; scans need the resolver — the corpus only exercises the pure layer. */
class PdfHeaderTagger(private val context: Context?) {

    enum class Category { BOARDING_PASS, E_TICKET, RECEIPT, INVOICE, MENU, STATEMENT, REPORT, DOCUMENT, OTHER }

    data class TaggedDoc(
        val uri: String,
        val displayName: String,
        val category: Category,
        val confidence: Float,
        val sizeBytes: Long,
        val modifiedAt: Long,
        val relativePath: String
    )

    private data class Rule(val category: Category, val patterns: List<Regex>, val confidence: Float)

    /** Filename/PATH signatures from real-world issuer conventions. */
    private val rules = listOf(
        Rule(Category.BOARDING_PASS, listOf(
            Regex("boarding.?pass", RegexOption.IGNORE_CASE),
            Regex("bp[_ -]?\\d{4,}", RegexOption.IGNORE_CASE)), 0.92f),
        Rule(Category.E_TICKET, listOf(
            Regex("e-?ticket", RegexOption.IGNORE_CASE),
            Regex("ticket[_ -]?(?:id|no)", RegexOption.IGNORE_CASE),
            Regex("\\bpnr\\b", RegexOption.IGNORE_CASE),
            Regex("indigo|vistara|spicejet|akasa|ixigo|makemytrip|cleartrip|irctc", RegexOption.IGNORE_CASE)), 0.90f),
        Rule(Category.RECEIPT, listOf(
            Regex("receipt", RegexOption.IGNORE_CASE),
            Regex("bill[_ -]?(?:paid|no)", RegexOption.IGNORE_CASE),
            Regex("order[_ -]?(?:summary|confirmation|details)", RegexOption.IGNORE_CASE),
            Regex("swiggy|zomato|zepto|blinkit|amazon order|flipkart order", RegexOption.IGNORE_CASE)), 0.88f),
        Rule(Category.INVOICE, listOf(
            Regex("invoice", RegexOption.IGNORE_CASE),
            Regex("gst[_ -]?(?:invoice|tax)", RegexOption.IGNORE_CASE)), 0.85f),
        Rule(Category.STATEMENT, listOf(
            Regex("statement", RegexOption.IGNORE_CASE),
            Regex("bank[_ -]?(?:statement|passbook)", RegexOption.IGNORE_CASE)), 0.82f),
        Rule(Category.REPORT, listOf(
            Regex("report", RegexOption.IGNORE_CASE),
            Regex("lab[_ -]?(?:result|test)", RegexOption.IGNORE_CASE),
            Regex("prescription", RegexOption.IGNORE_CASE)), 0.75f),
        Rule(Category.MENU, listOf(
            Regex("\\bmenu\\b", RegexOption.IGNORE_CASE),
            Regex("cafe", RegexOption.IGNORE_CASE),
            Regex("restaurant", RegexOption.IGNORE_CASE)), 0.70f),
    )

    /** Scan MediaStore for PDFs / docs and tag each. Throws SecurityException honestly when permission missing. */
    fun scanAndTag(): List<TaggedDoc> {
        val out = mutableListOf<TaggedDoc>()
        val collection = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.RELATIVE_PATH,
            MediaStore.Files.FileColumns.MIME_TYPE
        )
        val selection = MediaStore.Files.FileColumns.MIME_TYPE + " IN (?, ?, ?, ?)"
        val mimeArgs = arrayOf(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel"
        )
        try {
            val resolver = context?.contentResolver ?: return fallbackFilesystemScan()
            resolver.query(collection, projection, selection, mimeArgs, null)?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val iName = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val iSize = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val iDate = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
                val iPath = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.RELATIVE_PATH)
                while (c.moveToNext()) {
                    val id = c.getLong(iId)
                    val name = c.getString(iName) ?: continue
                    val size = c.getLong(iSize)
                    val date = c.getLong(iDate) * 1000
                    val relPath = c.getString(iPath) ?: ""
                    val (cat, conf) = classify(name, relPath)
                    out.add(TaggedDoc("content://media/external/file/$id", name, cat, conf, size, date, relPath))
                }
            }
            return out
        } catch (e: SecurityException) {
            // Honest failure: caller surfaces a permission prompt, not a fake success.
            throw e
        } catch (e: IllegalArgumentException) {
            // Column missing on exotic builds — degrade to public-dir name scan
            return fallbackFilesystemScan()
        }
    }

    fun classify(name: String, relativePath: String = ""): Pair<Category, Float> {
        val hay = "$name $relativePath"
        var best = Pair(Category.DOCUMENT, 0.40f)
        for (rule in rules) {
            for (rx in rule.patterns) {
                if (rx.containsMatchIn(hay)) {
                    if (rule.confidence > best.second) best = Pair(rule.category, rule.confidence)
                    break
                }
            }
        }
        return best
    }

    /** Degrade path: scan public Download/Documents dirs by name (no contentResolver). */
    private fun fallbackFilesystemScan(): List<TaggedDoc> {
        val docs = mutableListOf<TaggedDoc>()
        val roots = listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS))
        for (root in roots) {
            root.listFiles { f -> f.extension.equals("pdf", true) || f.extension.equals("docx", true) }?.forEach { f ->
                val (cat, conf) = classify(f.name, root.name)
                docs.add(TaggedDoc(f.toURI().toString(), f.name, cat, conf, f.length(), f.lastModified(), root.name))
            }
        }
        return docs
    }

    /** Junk candidates = old + small + weak-importance categories (the 'cleaner' half of the ask). */
    fun junkCandidates(docs: List<TaggedDoc>, olderThanDays: Int = 30, smallerThanKb: Int = 300): List<TaggedDoc> {
        val cutoff = System.currentTimeMillis() - olderThanDays * 86_400_000L
        val weakCats = setOf(Category.MENU, Category.OTHER, Category.RECEIPT)
        return docs.filter { it.modifiedAt < cutoff && it.sizeBytes < smallerThanKb * 1024L && it.category in weakCats }
    }
}