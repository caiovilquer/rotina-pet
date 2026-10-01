package dev.vilquer.petcarescheduler.infra.adapter.output.ai

import dev.vilquer.petcarescheduler.usecase.contract.drivenports.DocumentTextExtractorPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.ExtractedDocument
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.ExtractedDocumentPage
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.springframework.stereotype.Component

@Component
class PdfDocumentTextExtractorAdapter : DocumentTextExtractorPort {
    override val version = "pdfbox-v1"

    override fun extract(bytes: ByteArray, contentType: String): ExtractedDocument {
        require(contentType == "application/pdf") { "document_content_type_unsupported" }
        require(bytes.size in 1..MAX_PDF_BYTES) { "document_size_invalid" }
        Loader.loadPDF(bytes).use { document ->
            require(!document.isEncrypted) { "document_encrypted_unsupported" }
            require(document.numberOfPages in 1..MAX_PAGES) { "document_page_count_invalid" }
            var totalCharacters = 0
            val pages = (1..document.numberOfPages).mapNotNull { page ->
                val text = PDFTextStripper().apply {
                    startPage = page
                    endPage = page
                    sortByPosition = true
                }.getText(document).replace('\u0000', ' ').trim()
                totalCharacters += text.length
                require(totalCharacters <= MAX_EXTRACTED_CHARACTERS) { "document_text_too_large" }
                text.takeIf(String::isNotEmpty)?.let { ExtractedDocumentPage(page, it) }
            }
            require(pages.isNotEmpty()) { "document_has_no_extractable_text" }
            return ExtractedDocument(pages, version)
        }
    }

    companion object {
        private const val MAX_PDF_BYTES = 10 * 1024 * 1024
        private const val MAX_PAGES = 200
        private const val MAX_EXTRACTED_CHARACTERS = 1_000_000
    }
}
