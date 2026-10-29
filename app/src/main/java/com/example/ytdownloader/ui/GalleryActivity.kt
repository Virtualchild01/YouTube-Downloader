package com.example.ytdownloader.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.example.ytdownloader.R
import com.example.ytdownloader.data.DownloadStorage
import com.example.ytdownloader.data.DownloadedMedia
import com.example.ytdownloader.data.MediaSharer
import com.example.ytdownloader.databinding.ActivityGalleryBinding
import com.example.ytdownloader.databinding.SheetFileActionsBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Галерея скачанного: сетка файлов из Movies/YTDownloader и Music/YTDownloader. */
class GalleryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGalleryBinding
    private lateinit var adapter: GalleryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = GalleryAdapter(
            onOpen = { open(it) },
            onMore = { showActions(it) }
        )

        binding.rvGallery.layoutManager = GridLayoutManager(this, 2)
        binding.rvGallery.adapter = adapter

        binding.btnBack.setOnClickListener { finish() }
        binding.swipeRefresh.setOnRefreshListener { load() }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { DownloadStorage.list(applicationContext) }
            adapter.submit(items)
            binding.swipeRefresh.isRefreshing = false
            binding.layoutEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            binding.rvGallery.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
            binding.tvCount.text = if (items.isEmpty()) "" else items.size.toString()
        }
    }

    private fun open(media: DownloadedMedia) {
        if (media.isPlayableInside) {
            startActivity(
                Intent(this, PlayerActivity::class.java)
                    .putExtra(PlayerActivity.EXTRA_URI, media.uri.toString())
                    .putExtra(PlayerActivity.EXTRA_TITLE, media.displayName)
            )
        } else {
            // AVI и прочие контейнеры вне Media3 — отдаём системному плееру
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(media.uri, media.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, R.string.no_player_for_format, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Шторка с действиями над файлом: поделиться или удалить. */
    private fun showActions(media: DownloadedMedia) {
        val sheetBinding = SheetFileActionsBinding.inflate(layoutInflater)
        val sheet = BottomSheetDialog(this).apply { setContentView(sheetBinding.root) }

        sheetBinding.tvSheetTitle.text = media.displayName

        sheetBinding.rowShare.setOnClickListener {
            sheet.dismiss()
            MediaSharer.share(this, media)
        }

        sheetBinding.rowDelete.setOnClickListener {
            sheet.dismiss()
            confirmDelete(media)
        }

        sheet.show()
    }

    private fun confirmDelete(media: DownloadedMedia) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_title)
            .setMessage(media.displayName)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete_action) { _, _ ->
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        DownloadStorage.delete(applicationContext, media)
                    }
                    if (!ok) {
                        Toast.makeText(
                            this@GalleryActivity,
                            R.string.delete_failed,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    load()
                }
            }
            .show()
    }
}
