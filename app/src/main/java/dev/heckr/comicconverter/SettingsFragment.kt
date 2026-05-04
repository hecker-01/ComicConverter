package dev.heckr.comicconverter

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.format.Formatter
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.heckr.comicconverter.databinding.FragmentSettingsBinding
import dev.heckr.comicconverter.updater.AppUpdater
import dev.heckr.comicconverter.updater.UpdateChecker
import io.noties.markwon.Markwon

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var appUpdater: AppUpdater

    private val outputFolderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            requireContext().contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            AppSettings.setOutputFolderUri(requireContext(), uri)
            updateOutputFolderDisplay()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appUpdater = AppUpdater(this)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Callback 1: State changes
        appUpdater.onStateChanged = fun(state: AppUpdater.State, message: String) {
            if (_binding == null) return
            binding.updateSubtitle.text = message
            when (state) {
                AppUpdater.State.DOWNLOADING,
                AppUpdater.State.INSTALLING -> {
                    binding.updateProgress.isVisible = true
                    binding.updateCard.isClickable = false
                    binding.updateCard.isFocusable = false
                }
                else -> {
                    binding.updateProgress.isVisible = false
                    binding.updateCard.isClickable = true
                    binding.updateCard.isFocusable = true
                }
            }
        }

        // Callback 2: Download progress
        appUpdater.onDownloadProgress = fun(progress: Int) {
            if (_binding == null) return
            if (progress < 0) {
                binding.updateProgress.isIndeterminate = true
            } else {
                binding.updateProgress.isIndeterminate = false
                binding.updateProgress.setProgressCompat(progress, true)
            }
        }

        // Initial sync
        appUpdater.syncFromChecker()

        // Card click handler
        binding.updateCard.setOnClickListener {
            if (appUpdater.onUpdateTapped(requireContext())) {
                showUpdateDialog()
            }
        }

        // Output folder card
        binding.outputFolderCard.setOnClickListener {
            outputFolderPicker.launch(AppSettings.getOutputFolderUri(requireContext()))
        }
        binding.resetOutputFolder.setOnClickListener {
            AppSettings.clearOutputFolderUri(requireContext())
            updateOutputFolderDisplay()
        }
        updateOutputFolderDisplay()

        // Version info
        binding.versionInfo.text = getString(
            R.string.version_format,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE
        )

        // Footer with clickable link
        val footerText = getString(R.string.copyright_footer)
        val linkStart = footerText.indexOf("heckr.dev")
        val spannable = SpannableString(footerText)
        spannable.setSpan(object : ClickableSpan() {
            override fun onClick(widget: View) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://heckr.dev")))
            }
        }, linkStart, linkStart + "heckr.dev".length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        binding.copyrightFooter.text = spannable
        binding.copyrightFooter.movementMethod = LinkMovementMethod.getInstance()
    }

    private fun showUpdateDialog() {
        val ctx = context ?: return
        val version = UpdateChecker.latestVersion ?: return
        val body = UpdateChecker.releaseBody
        val sizeBytes = UpdateChecker.apkSizeBytes

        val dialogView = layoutInflater.inflate(R.layout.dialog_update, null)

        dialogView.findViewById<TextView>(R.id.update_size).text =
            getString(R.string.update_dialog_size_format,
                Formatter.formatFileSize(ctx, sizeBytes))

        val changelogView = dialogView.findViewById<TextView>(R.id.update_changelog)
        if (!body.isNullOrBlank()) {
            val markwon = Markwon.create(ctx)
            markwon.setMarkdown(changelogView, body)
        } else {
            changelogView.text = getString(R.string.no_changelog)
        }

        MaterialAlertDialogBuilder(ctx)
            .setTitle(getString(R.string.update_dialog_title_format, version))
            .setView(dialogView)
            .setPositiveButton(R.string.update_button) { dialog, _ ->
                dialog.dismiss()
                appUpdater.startDownload(ctx)
            }
            .setNegativeButton(R.string.cancel_button, null)
            .show()
    }

    private fun updateOutputFolderDisplay() {
        if (_binding == null) return
        val ctx = context ?: return
        binding.outputFolderSubtitle.text = AppSettings.getDisplayPath(ctx)
        binding.resetOutputFolder.isVisible = AppSettings.getOutputFolderUri(ctx) != null
    }

    override fun onDestroyView() {
        appUpdater.cleanup()
        super.onDestroyView()
        _binding = null
    }
}
