package dev.heckr.comicconverter

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
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
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.DynamicColors
import dev.heckr.comicconverter.converter.*
import dev.heckr.comicconverter.updater.UpdateChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var selectFileButton: Button
    private lateinit var selectFolderButton: Button
    private lateinit var detectionCard: MaterialCardView
    private lateinit var detectedLabel: TextView
    private lateinit var outputChipGroup: ChipGroup
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private var badgeDot: View? = null

    private var detectedInput: DetectedInput? = null

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                handleFileSelected(uri)
            }
        }
    }

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                handleFolderSelected(uri)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        selectFileButton = findViewById(R.id.selectFileButton)
        selectFolderButton = findViewById(R.id.selectFolderButton)
        detectionCard = findViewById(R.id.detectionCard)
        detectedLabel = findViewById(R.id.detectedLabel)
        outputChipGroup = findViewById(R.id.outputChipGroup)
        progressBar = findViewById(R.id.progressBar)
        statusText = findViewById(R.id.statusText)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        selectFileButton.setOnClickListener { openFilePicker() }
        selectFolderButton.setOnClickListener { openFolderPicker() }

        UpdateChecker.check(this)
        UpdateChecker.addListener(updateBadgeListener)
    }

    override fun onDestroy() {
        UpdateChecker.removeListener(updateBadgeListener)
        super.onDestroy()
    }

    private val updateBadgeListener: () -> Unit = {
        badgeDot?.visibility = if (UpdateChecker.updateAvailable) View.VISIBLE else View.GONE
    }

    override fun onResume() {
        super.onResume()
        badgeDot?.visibility = if (UpdateChecker.updateAvailable) View.VISIBLE else View.GONE
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        val settingsItem = menu.findItem(R.id.action_settings)
        settingsItem.actionView?.let {
            badgeDot = it.findViewById(R.id.badge_dot)
            badgeDot?.visibility = if (UpdateChecker.updateAvailable) View.VISIBLE else View.GONE
            it.setOnClickListener { onOptionsItemSelected(settingsItem) }
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_settings -> {
            startActivity(Intent(this, SettingsActivity::class.java))
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    // region Input selection

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES, arrayOf(
                    "application/zip",
                    "application/x-cbz",
                    "application/x-rar-compressed",
                    "application/vnd.rar",
                    "application/pdf",
                    "application/epub+zip"
                )
            )
        }
        filePickerLauncher.launch(intent)
    }

    private fun openFolderPicker() {
        folderPickerLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE))
    }

    private fun handleFileSelected(uri: Uri) {
        try {
            val input = FormatDetector.detectFile(this, uri)
            showDetectionCard(input)
        } catch (e: Exception) {
            statusText.text = getString(R.string.error, e.message)
        }
    }

    private fun handleFolderSelected(uri: Uri) {
        try {
            val input = FormatDetector.detectFolder(this, uri)
            showDetectionCard(input)
        } catch (e: Exception) {
            statusText.text = getString(R.string.error, e.message)
        }
    }

    // endregion

    // region Detection card

    private fun showDetectionCard(input: DetectedInput) {
        detectedInput = input
        detectedLabel.text = input.displayLabel
        outputChipGroup.removeAllViews()

        input.format.availableOutputFormats().forEach { fmt ->
            val chip = Chip(this).apply {
                text = fmt.displayName
                isCheckable = false
                setOnClickListener { startConversion(input, fmt) }
            }
            outputChipGroup.addView(chip)
        }

        detectionCard.visibility = View.VISIBLE
        statusText.text = getString(R.string.choose_output_format)
    }

    // endregion

    // region Conversion

    private fun startConversion(input: DetectedInput, outputFormat: OutputFormat) {
        CoroutineScope(Dispatchers.Main).launch {
            setUiBusy(true)
            progressBar.progress = 0
            progressBar.visibility = View.VISIBLE
            detectionCard.visibility = View.GONE

            try {
                val result = ConversionEngine.convert(this@MainActivity, input, outputFormat) { status, percent ->
                    withContext(Dispatchers.Main) {
                        statusText.text = status
                        progressBar.progress = percent
                    }
                }
                progressBar.visibility = View.GONE
                statusText.text = getString(R.string.conversion_complete, result)
                Toast.makeText(this@MainActivity, getString(R.string.done), Toast.LENGTH_LONG).show()
                detectionCard.visibility = View.VISIBLE
            } catch (e: Exception) {
                progressBar.visibility = View.GONE
                statusText.text = getString(R.string.error, e.message)
                Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                detectionCard.visibility = View.VISIBLE
                e.printStackTrace()
            } finally {
                setUiBusy(false)
            }
        }
    }

    private fun setUiBusy(busy: Boolean) {
        selectFileButton.isEnabled = !busy
        selectFolderButton.isEnabled = !busy
        outputChipGroup.children().forEach { it.isEnabled = !busy }
    }

    // ChipGroup doesn't expose children directly
    private fun ChipGroup.children(): List<View> =
        (0 until childCount).map { getChildAt(it) }

    // endregion

    // Unused but required override
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }
}
