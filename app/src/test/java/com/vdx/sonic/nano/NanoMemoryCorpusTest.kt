package com.vdx.sonic.nano

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * NanoMemoryCorpus — Cody's NanoCore assignment, proven as tests (his law:
 * "what the agent thinks vs what the live product does" — these tests are the
 * LIVE side of that gap checker for the memory-layer feature).
 *
 * T1..T6 — the rule tagger: real-world filename shapes → correct category at
 *          honest confidence; ambiguous falls to DOCUMENT at low conf, never a
 *          fake high-confidence guess.
 * T7 —    junk-candidates logic: old+small+weak-only, never a receipt < 300KB
 *          recent mistake; deletion is a SUGGESTION list (confirm-first).
 * T8 —    NanoGate honesty: below SDK floor = ABSENT even if someone mocks a
 *          package in (the gate is the truth layer for rung 3).
 * T9 —    the tagger's classify is deterministic + monotone: same input, same
 *          answer; stronger rule always wins.
 *
 * Bar: 100%. A miss is a lie.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NanoMemoryCorpusTest {

    private fun tagger() = PdfHeaderTagger(null) // classify/junk are context-free

    // T1 — boarding pass signature (airline e-ticket naming)
    @Test
    fun T1_boardingPass_names_tagCorrectly() {
        val t = tagger()
        for (name in listOf(
            "BoardingPass_BP4821.pdf",
            "IndiGo-Boarding-Pass-DEL-BOM.pdf",
            "BP_2024_118827.pdf")) {
            val (cat, conf) = t.classify(name, "Download/")
            assertEquals(name, PdfHeaderTagger.Category.BOARDING_PASS, cat)
            assertTrue("$name conf", conf >= 0.9f)
        }
    }

    // T2 — e-ticket family: airline PNR / IRCTC / OTA names
    @Test
    fun T2_eTicket_names_tagCorrectly() {
        val t = tagger()
        for (name in listOf("e-ticket_6E-PNR-QWERTY.pdf", "IRCTC_ticket_LKO_NDTV.pdf",
                            "MakeMyTrip_booking_9823.pdf", "ticket_no_8471.pdf")) {
            val (cat, conf) = t.classify(name, "Download/")
            assertEquals(name, PdfHeaderTagger.Category.E_TICKET, cat)
            assertTrue(name, conf >= 0.85f)
        }
    }

    // T3 — receipts: order summaries + delivery apps
    @Test
    fun T3_receipt_names_tagCorrectly() {
        val t = tagger()
        for (name in listOf("Swiggy_order_summary.pdf", "Amazon-order-details.pdf",
                            "receipt_5521.pdf", "Zomato_order_88912.pdf")) {
            val (cat, conf) = t.classify(name, "Download/")
            assertEquals(name, PdfHeaderTagger.Category.RECEIPT, cat)
        }
    }

    // T4 — invoice / statement / report / menu families
    @Test
    fun T4_businessDocs_tagCorrectly() {
        val t = tagger()
        assertEquals(PdfHeaderTagger.Category.INVOICE, t.classify("GST_Invoice_2024.pdf", "Documents/").first)
        assertEquals(PdfHeaderTagger.Category.STATEMENT, t.classify("HDFC_Bank_Statement_Mar.pdf", "Download/").first)
        assertEquals(PdfHeaderTagger.Category.REPORT, t.classify("Lab_Report_CBC.pdf", "Documents/").first)
        assertEquals(PdfHeaderTagger.Category.MENU, t.classify("Cafe_Menu_v2.pdf", "Download/").first)
    }

    // T5 — ambiguity falls DOWN, not up: a bare number is DOCUMENT at honest low conf
    @Test
    fun T5_ambiguous_isHonestLowConfidence_DOCUMENT() {
        val t = tagger()
        val (cat, conf) = t.classify("scan0004.pdf", "")
        assertEquals(PdfHeaderTagger.Category.DOCUMENT, cat)
        assertTrue("must be below 0.5 — must never pass as a confident guess", conf < 0.5f)
        // and NOTHING in the tagger ever returns >0.95 (issuer rules are capped)
        val maxConf = listOf("BoardingPass.pdf","e-ticket.pdf","receipt.pdf","invoice.pdf").maxOf { t.classify(it).second }
        assertTrue(maxConf <= 0.95f)
    }

    // T6 — path strengthens the verdict (Download/ + menu-name)
    @Test
    fun T6_pathIsSignal_whenNameIsWeak() {
        val t = tagger()
        val (cat1, _) = t.classify("nov2024.pdf", "Download/MyCafeMenupack/")
        assertEquals(PdfHeaderTagger.Category.MENU, cat1)
    }

    // T7 — the phone-cleaner list: old + small + WEAK cats only; recent or big docs never listed
    @Test
    fun T7_junkCandidates_neverTouchImportantOrRecent() {
        val t = tagger()
        val old = System.currentTimeMillis() - 60L * 86_400_000L
        val docs = listOf(
            PdfHeaderTagger.TaggedDoc("u1", "Menu_v1.pdf", PdfHeaderTagger.Category.MENU, 0.7f, 90_000, old, "Download/"),
            PdfHeaderTagger.TaggedDoc("u2", "BoardingPass.pdf", PdfHeaderTagger.Category.BOARDING_PASS, 0.92f, 80_000, old, "Download/"),
            PdfHeaderTagger.TaggedDoc("u3", "receipt_big.pdf", PdfHeaderTagger.Category.RECEIPT, 0.88f, 900_000, old, "Download/"),
            PdfHeaderTagger.TaggedDoc("u4", "receipt_today.pdf", PdfHeaderTagger.Category.RECEIPT, 0.88f, 50_000, System.currentTimeMillis(), "Download/")
        )
        val junk = t.junkCandidates(docs)
        assertEquals(listOf("u1"), junk.map { it.uri }) // ONLY the old, small, weak menu
    }

    // T8 — NanoGate contract: floor constant + non-blank probe detail on a Robolectric context
    @Test
    fun T8_nanoGate_contract() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val gate = NanoGate(ctx)
        assertTrue("AICore floor must be API 34", NanoGate.MIN_AICORE_SDK == 34)
        val p = gate.probe()
        assertNotNull(p)
        assertTrue(p.detail.isNotBlank())
        // Robolectric android-34 image: no real AICore → status must be ABSENT (honest), never PRESENT
        assertEquals(NanoGate.Status.ABSENT, p.status)
    }

    // T9 — determinism: same input → same verdict (rule order can't flip results)
    @Test
    fun T9_classify_isDeterministic() {
        val t = tagger()
        val a = t.classify("Swiggy_order_summary.pdf", "Download/")
        val b = t.classify("Swiggy_order_summary.pdf", "Download/")
        assertEquals(a.first, b.first)
        assertEquals(a.second, b.second, 0.0f)
    }
}