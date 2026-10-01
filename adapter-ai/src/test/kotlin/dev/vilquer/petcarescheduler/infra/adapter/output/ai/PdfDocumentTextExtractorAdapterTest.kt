package dev.vilquer.petcarescheduler.infra.adapter.output.ai

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

class PdfDocumentTextExtractorAdapterTest {
    @Test
    fun `extracts text preserving the page used for citation`() {
        val bytes = PDDocument().use { document ->
            document.addPage(PDPage())
            PDPageContentStream(document, document.getPage(0)).use { content ->
                content.beginText()
                content.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                content.newLineAtOffset(72f, 720f)
                content.showText("Hemoglobina dentro da faixa registrada")
                content.endText()
            }
            ByteArrayOutputStream().also(document::save).toByteArray()
        }

        val result = PdfDocumentTextExtractorAdapter().extract(bytes, "application/pdf")

        assertEquals(1, result.pages.single().page)
        assertTrue(result.pages.single().text.contains("Hemoglobina"))
    }
}
