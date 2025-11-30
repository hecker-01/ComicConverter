package net.heckerdev.comicconverter

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.documentfile.provider.DocumentFile
import com.google.android.material.color.DynamicColors
import com.itextpdf.io.image.ImageDataFactory
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.Image
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

class MainActivity : AppCompatActivity() {
    private lateinit var selectFileButton: Button
    private lateinit var selectFolderButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView

    private val REQUEST_PERMISSION_CODE = 100

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                convertCbzToPdf(uri)
            }
        }
    }

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                convertFolderToPdf(uri)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Apply Material You dynamic colors
        DynamicColors.applyToActivityIfAvailable(this)
        
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        selectFileButton = findViewById(R.id.selectFileButton)
        selectFolderButton = findViewById(R.id.selectFolderButton)
        progressBar = findViewById(R.id.progressBar)
        statusText = findViewById(R.id.statusText)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        selectFileButton.setOnClickListener {
            checkPermissionsAndOpenFilePicker()
        }

        selectFolderButton.setOnClickListener {
            openFolderPicker()
        }
    }

    private fun checkPermissionsAndOpenFilePicker() {
        // Android 11+ (API 31 is minSdk) uses scoped storage, no permissions needed for file picker
        openFilePicker()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                openFilePicker()
            } else {
                Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-cbz"))
        }
        filePickerLauncher.launch(intent)
    }

    private fun convertCbzToPdf(uri: Uri) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                selectFileButton.isEnabled = false
                selectFolderButton.isEnabled = false
                progressBar.progress = 0
                progressBar.visibility = View.VISIBLE
                statusText.text = getString(R.string.reading_cbz_file)

                val result = withContext(Dispatchers.IO) {
                    processCbzFile(uri)
                }

                progressBar.visibility = View.GONE
                statusText.text = getString(R.string.pdf_created_successfully, result)
                Toast.makeText(this@MainActivity, "Conversion complete!", Toast.LENGTH_LONG).show()

            } catch (e: Exception) {
                progressBar.visibility = View.GONE
                statusText.text = getString(R.string.error, e.message)
                Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                e.printStackTrace()
            } finally {
                selectFileButton.isEnabled = true
                selectFolderButton.isEnabled = true
            }
        }
    }

    private suspend fun processCbzFile(uri: Uri): String = withContext(Dispatchers.IO) {
        val fileName = getFileName(uri)
        statusText.post { statusText.text = getString(R.string.processing, fileName) }

        val images = mutableListOf<Pair<String, ByteArray>>()
        var metadata: JSONObject? = null

        // Extract CBZ contents
        contentResolver.openInputStream(uri)?.use { inputStream ->
            ZipInputStream(inputStream).use { zipInputStream ->
                var entry = zipInputStream.nextEntry

                while (entry != null) {
                    val entryName = entry.name

                    when {
                        entryName.equals("index.json", ignoreCase = true) -> {
                            val content = zipInputStream.readBytes()
                            metadata = JSONObject(String(content))
                        }
                        entryName.matches(Regex(".*\\.(jpg|jpeg|png|gif|bmp|webp)", RegexOption.IGNORE_CASE)) -> {
                            val imageData = zipInputStream.readBytes()
                            images.add(entryName to imageData)
                        }
                    }

                    zipInputStream.closeEntry()
                    entry = zipInputStream.nextEntry
                }
            }
        }

        if (images.isEmpty()) {
            throw Exception("No images found in CBZ file")
        }

        // Sort images by name (natural order)
        images.sortBy { it.first.lowercase() }

        // Extract metadata
        val title = metadata?.optString("title") ?: fileName.replace(".cbz", "")

        statusText.post { statusText.text = getString(R.string.creating_pdf_with_pages, images.size) }

        // Create PDF in public Documents directory
        val outputDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "ComicConverter")

        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }

        val pdfFile = File(outputDir, "${title}.pdf")

        PdfWriter(FileOutputStream(pdfFile)).use { writer ->
            PdfDocument(writer).use { pdfDoc ->
                // Set PDF metadata
                val info = pdfDoc.documentInfo
                info.title = title
                metadata?.optString("author")?.let { if (it.isNotEmpty()) info.author = it }
                metadata?.optString("publisher")?.let { if (it.isNotEmpty()) info.subject = it }

                Document(pdfDoc).use { document ->
                    document.setMargins(0f, 0f, 0f, 0f)

                    // Add all images as pages
                    images.forEachIndexed { index, (_, imageData) ->
                        val progress = ((index + 1) * 100 / images.size)
                        progressBar.post {
                            progressBar.progress = progress
                        }
                        statusText.post {
                            statusText.text =
                                getString(R.string.adding_page_of, index + 1, images.size)
                        }

                        val bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.size)
                        val outputStream = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
                        val compressedImageData = outputStream.toByteArray()

                        val imageData = ImageDataFactory.create(compressedImageData)
                        val image = Image(imageData)

                        // Scale image to fit page
                        val pageWidth = 595f // A4 width in points
                        val pageHeight = 842f // A4 height in points

                        val scale = minOf(
                            pageWidth / bitmap.width,
                            pageHeight / bitmap.height
                        )

                        image.scaleToFit(bitmap.width * scale, bitmap.height * scale)
                        image.setFixedPosition(
                            (pageWidth - bitmap.width * scale) / 2,
                            (pageHeight - bitmap.height * scale) / 2
                        )

                        if (index > 0) {
                            document.add(com.itextpdf.layout.element.AreaBreak())
                        }

                        document.add(image)
                        bitmap.recycle()
                    }
                }
            }
        }

        "PDF saved to: ${pdfFile.absolutePath}"
    }

    private fun getFileName(uri: Uri): String {
        var result = "comic.cbz"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) {
                    result = cursor.getString(nameIndex)
                }
            }
        }
        return result
    }

    private fun openFolderPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        folderPickerLauncher.launch(intent)
    }

    private fun convertFolderToPdf(uri: Uri) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                selectFileButton.isEnabled = false
                selectFolderButton.isEnabled = false
                progressBar.progress = 0
                progressBar.visibility = View.VISIBLE
                statusText.text = getString(R.string.reading_folder_contents)

                val result = withContext(Dispatchers.IO) {
                    processFolderContents(uri)
                }

                progressBar.visibility = View.GONE
                statusText.text = getString(R.string.all_chapters_converted_successfully, result)
                Toast.makeText(this@MainActivity, "Folder conversion complete!", Toast.LENGTH_LONG).show()

            } catch (e: Exception) {
                progressBar.visibility = View.GONE
                statusText.text = getString(R.string.error, e.message)
                Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                e.printStackTrace()
            } finally {
                selectFileButton.isEnabled = true
                selectFolderButton.isEnabled = true
            }
        }
    }

    private suspend fun processFolderContents(uri: Uri): String = withContext(Dispatchers.IO) {
        statusText.post { statusText.text = getString(R.string.scanning_folder) }

        // Take persistent permission
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )

        // Get folder name
        val folderName = getFolderName(uri)

        // Create output directory
        val outputDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "ComicConverter/$folderName"
        )

        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }

        // Find all CBZ files, metadata, and cover image
        val documentFile = DocumentFile.fromTreeUri(this@MainActivity, uri)
            ?: throw Exception("Cannot access folder")

        var metadata: JSONObject? = null
        var coverImageData: ByteArray? = null
        val cbzFiles = mutableListOf<DocumentFile>()

        documentFile.listFiles().forEach { file ->
            when {
                file.name?.equals("index.json", ignoreCase = true) == true -> {
                    contentResolver.openInputStream(file.uri)?.use { inputStream ->
                        val content = inputStream.readBytes()
                        metadata = JSONObject(String(content))
                    }
                }
                file.name?.matches(Regex("cover\\.(jpg|jpeg|png|gif|bmp|webp)", RegexOption.IGNORE_CASE)) == true -> {
                    contentResolver.openInputStream(file.uri)?.use { inputStream ->
                        coverImageData = inputStream.readBytes()
                    }
                }
                file.name?.endsWith(".cbz", ignoreCase = true) == true -> {
                    cbzFiles.add(file)
                }
            }
        }

        if (cbzFiles.isEmpty()) {
            throw Exception("No CBZ files found in folder")
        }

        // Sort CBZ files by name
        cbzFiles.sortBy { it.name?.lowercase() }

        statusText.post {
            statusText.text = getString(R.string.found_chapters_processing, cbzFiles.size)
        }

        // Process each CBZ file
        cbzFiles.forEachIndexed { index, cbzFile ->
            val progress = ((index + 1) * 100 / cbzFiles.size)
            progressBar.post {
                progressBar.progress = progress
            }
            statusText.post {
                statusText.text = getString(
                    R.string.processing_chapter_of,
                    index + 1,
                    cbzFiles.size,
                    cbzFile.name
                )
            }

            processChapterCbz(cbzFile, outputDir, metadata, coverImageData)
        }

        "Saved ${cbzFiles.size} chapters to: ${outputDir.absolutePath}"
    }

    private fun getFolderName(uri: Uri): String {
        val documentFile = DocumentFile.fromTreeUri(this, uri)
        return documentFile?.name ?: "Comic"
    }

    private suspend fun processChapterCbz(
        cbzFile: DocumentFile,
        outputDir: File,
        comicMetadata: JSONObject?,
        coverImageData: ByteArray?
    ) = withContext(Dispatchers.IO) {
        val fileName = cbzFile.name ?: "chapter.cbz"
        val images = mutableListOf<Pair<String, ByteArray>>()
        var chapterMetadata: JSONObject? = null

        // Extract CBZ contents
        contentResolver.openInputStream(cbzFile.uri)?.use { inputStream ->
            ZipInputStream(inputStream).use { zipInputStream ->
                var entry = zipInputStream.nextEntry

                while (entry != null) {
                    val entryName = entry.name

                    when {
                        entryName.equals("index.json", ignoreCase = true) -> {
                            val content = zipInputStream.readBytes()
                            chapterMetadata = JSONObject(String(content))
                        }
                        entryName.matches(Regex(".*\\.(jpg|jpeg|png|gif|bmp|webp)", RegexOption.IGNORE_CASE)) -> {
                            val imageData = zipInputStream.readBytes()
                            images.add(entryName to imageData)
                        }
                    }

                    zipInputStream.closeEntry()
                    entry = zipInputStream.nextEntry
                }
            }
        }

        if (images.isEmpty()) {
            throw Exception("No images found in $fileName")
        }

        // Sort images by name
        images.sortBy { it.first.lowercase() }

        // Extract chapter title from metadata or filename
        val chapterTitle = chapterMetadata?.optString("title")
            ?: fileName.replace(".cbz", "")

        // Create PDF
        val pdfFile = File(outputDir, "${chapterTitle}.pdf")

        PdfWriter(FileOutputStream(pdfFile)).use { writer ->
            PdfDocument(writer).use { pdfDoc ->
                // Set PDF metadata
                val info = pdfDoc.documentInfo
                info.title = chapterTitle

                // Use chapter metadata first, fall back to comic metadata
                val author = chapterMetadata?.optString("author")?.takeIf { it.isNotEmpty() }
                    ?: comicMetadata?.optString("author")?.takeIf { it.isNotEmpty() }
                author?.let { info.author = it }

                val publisher = chapterMetadata?.optString("publisher")?.takeIf { it.isNotEmpty() }
                    ?: comicMetadata?.optString("publisher")?.takeIf { it.isNotEmpty() }
                publisher?.let { info.subject = it }

                Document(pdfDoc).use { document ->
                    document.setMargins(0f, 0f, 0f, 0f)

                    var pageIndex = 0

                    // Add cover image as first page if available
                    coverImageData?.let { coverData ->
                        val bitmap = BitmapFactory.decodeByteArray(coverData, 0, coverData.size)
                        val outputStream = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
                        val compressedImageData = outputStream.toByteArray()

                        val imageData = ImageDataFactory.create(compressedImageData)
                        val image = Image(imageData)

                        // Scale image to fit page
                        val pageWidth = 595f // A4 width in points
                        val pageHeight = 842f // A4 height in points

                        val scale = minOf(
                            pageWidth / bitmap.width,
                            pageHeight / bitmap.height
                        )

                        image.scaleToFit(bitmap.width * scale, bitmap.height * scale)
                        image.setFixedPosition(
                            (pageWidth - bitmap.width * scale) / 2,
                            (pageHeight - bitmap.height * scale) / 2
                        )

                        document.add(image)
                        bitmap.recycle()
                        pageIndex++
                    }

                    // Add all chapter images as pages
                    images.forEach { (_, imageData) ->
                        val bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.size)
                        val outputStream = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
                        val compressedImageData = outputStream.toByteArray()

                        val imageData = ImageDataFactory.create(compressedImageData)
                        val image = Image(imageData)

                        // Scale image to fit page
                        val pageWidth = 595f // A4 width in points
                        val pageHeight = 842f // A4 height in points

                        val scale = minOf(
                            pageWidth / bitmap.width,
                            pageHeight / bitmap.height
                        )

                        image.scaleToFit(bitmap.width * scale, bitmap.height * scale)
                        image.setFixedPosition(
                            (pageWidth - bitmap.width * scale) / 2,
                            (pageHeight - bitmap.height * scale) / 2
                        )

                        if (pageIndex > 0) {
                            document.add(com.itextpdf.layout.element.AreaBreak())
                        }

                        document.add(image)
                        bitmap.recycle()
                        pageIndex++
                    }
                }
            }
        }
    }
}