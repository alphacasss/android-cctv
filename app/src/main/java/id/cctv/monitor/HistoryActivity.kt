package id.cctv.monitor

import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import id.cctv.monitor.data.MediaItem
import id.cctv.monitor.data.MediaSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {

    private enum class FilterType { ALL, PHOTOS, VIDEOS }

    private lateinit var toolbar: MaterialToolbar
    private lateinit var chipGroup: ChipGroup
    private lateinit var tvSummary: TextView
    private lateinit var rvHistory: RecyclerView
    private lateinit var layoutEmpty: View
    private val adapter = HistoryAdapter()

    private var allItems = listOf<MediaItem>()
    private var currentFilter = FilterType.ALL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        toolbar = findViewById(R.id.toolbarHistory)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.inflateMenu(R.menu.menu_history)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_delete_all -> {
                    confirmDeleteAll()
                    true
                }
                else -> false
            }
        }

        tvSummary = findViewById(R.id.tvHistorySummary)
        layoutEmpty = findViewById(R.id.layoutEmpty)
        chipGroup = findViewById(R.id.chipGroupFilter)
        rvHistory = findViewById(R.id.rvHistory)

        rvHistory.layoutManager = LinearLayoutManager(this)
        rvHistory.adapter = adapter

        adapter.onItemClick = { item ->
            if (item.isVideo) {
                playVideo(item)
            } else {
                showPhotoDialog(item)
            }
        }

        adapter.onShareClick = { item ->
            val mime = if (item.isVideo) "video/mp2t" else "image/jpeg"
            MediaSaver.share(this, item.file, mime)
        }

        adapter.onDeleteClick = { item ->
            confirmDeleteOne(item)
        }

        chipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            currentFilter = when {
                checkedIds.contains(R.id.chipPhotos) -> FilterType.PHOTOS
                checkedIds.contains(R.id.chipRecordings) -> FilterType.VIDEOS
                else -> FilterType.ALL
            }
            applyFilter()
        }
    }

    override fun onResume() {
        super.onResume()
        loadMedia()
    }

    private fun loadMedia() {
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) {
                MediaSaver.listAllMedia(this@HistoryActivity)
            }
            allItems = items
            applyFilter()
        }
    }

    private fun applyFilter() {
        val filtered = when (currentFilter) {
            FilterType.ALL -> allItems
            FilterType.PHOTOS -> allItems.filter { !it.isVideo }
            FilterType.VIDEOS -> allItems.filter { it.isVideo }
        }

        adapter.submitList(filtered)

        if (filtered.isEmpty()) {
            layoutEmpty.visibility = View.VISIBLE
            rvHistory.visibility = View.GONE
            tvSummary.text = "0 item tersimpan"
        } else {
            layoutEmpty.visibility = View.GONE
            rvHistory.visibility = View.VISIBLE
            val totalBytes = filtered.sumOf { it.sizeBytes }
            tvSummary.text = "${filtered.size} item • ${formatFileSize(totalBytes)}"
        }
    }

    private fun confirmDeleteOne(item: MediaItem) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Hapus File?")
            .setMessage("File ${item.name} akan dihapus secara permanen.")
            .setPositiveButton("Hapus") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        MediaSaver.deleteMedia(this@HistoryActivity, item.file)
                    }
                    Toast.makeText(this@HistoryActivity, "File dihapus", Toast.LENGTH_SHORT).show()
                    loadMedia()
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun confirmDeleteAll() {
        if (allItems.isEmpty()) {
            Toast.makeText(this, "Tidak ada file untuk dihapus", Toast.LENGTH_SHORT).show()
            return
        }

        val targetCount = when (currentFilter) {
            FilterType.ALL -> allItems.size
            FilterType.PHOTOS -> allItems.count { !it.isVideo }
            FilterType.VIDEOS -> allItems.count { it.isVideo }
        }

        if (targetCount == 0) {
            Toast.makeText(this, "Tidak ada file pada filter ini", Toast.LENGTH_SHORT).show()
            return
        }

        val filterLabel = when (currentFilter) {
            FilterType.ALL -> "semua riwayat ($targetCount file)"
            FilterType.PHOTOS -> "semua foto ($targetCount file)"
            FilterType.VIDEOS -> "semua rekaman ($targetCount file)"
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Hapus $filterLabel?")
            .setMessage("Tindakan ini akan menghapus $targetCount file secara permanen dari perangkat.")
            .setPositiveButton("Hapus Semua") { _, _ ->
                lifecycleScope.launch {
                    val toDelete = when (currentFilter) {
                        FilterType.ALL -> allItems
                        FilterType.PHOTOS -> allItems.filter { !it.isVideo }
                        FilterType.VIDEOS -> allItems.filter { it.isVideo }
                    }
                    withContext(Dispatchers.IO) {
                        toDelete.forEach { MediaSaver.deleteMedia(this@HistoryActivity, it.file) }
                    }
                    Toast.makeText(this@HistoryActivity, "$targetCount file telah dihapus", Toast.LENGTH_SHORT).show()
                    loadMedia()
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun playVideo(item: MediaItem) {
        val uri: Uri = try {
            FileProvider.getUriForFile(this, "${packageName}.files", item.file)
        } catch (t: Throwable) {
            Toast.makeText(this, "Gagal membuka file video: ${t.message}", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Tidak ada pemutar video di perangkat. Gunakan tombol Bagikan.", Toast.LENGTH_LONG).show()
        }
    }

    private fun showPhotoDialog(item: MediaItem) {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_view_photo)

        val ivFull = dialog.findViewById<ImageView>(R.id.ivFullPhoto)
        val tvTitle = dialog.findViewById<TextView>(R.id.tvDialogTitle)
        val btnClose = dialog.findViewById<ImageButton>(R.id.btnDialogClose)
        val btnShare = dialog.findViewById<ImageButton>(R.id.btnDialogShare)
        val btnDelete = dialog.findViewById<ImageButton>(R.id.btnDialogDelete)
        val progress = dialog.findViewById<ProgressBar>(R.id.progressFullPhoto)

        val timeStr = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(item.lastModified))
        tvTitle.text = "${item.cameraName} • $timeStr"

        btnClose.setOnClickListener { dialog.dismiss() }

        btnShare.setOnClickListener {
            MediaSaver.share(this, item.file, "image/jpeg")
        }

        btnDelete.setOnClickListener {
            dialog.dismiss()
            confirmDeleteOne(item)
        }

        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    BitmapFactory.decodeFile(item.file.absolutePath)
                } catch (_: Throwable) {
                    null
                }
            }
            progress.visibility = View.GONE
            if (bitmap != null) {
                ivFull.setImageBitmap(bitmap)
            } else {
                Toast.makeText(this@HistoryActivity, "Gagal memuat foto", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private class HistoryAdapter : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

        private val items = mutableListOf<MediaItem>()

        var onItemClick: ((MediaItem) -> Unit)? = null
        var onShareClick: ((MediaItem) -> Unit)? = null
        var onDeleteClick: ((MediaItem) -> Unit)? = null

        fun submitList(newItems: List<MediaItem>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_history, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.bind(item)
        }

        override fun getItemCount(): Int = items.size

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val ivThumb: ImageView = itemView.findViewById(R.id.ivThumb)
            private val ivPlayBadge: ImageView = itemView.findViewById(R.id.ivPlayBadge)
            private val tvCameraTitle: TextView = itemView.findViewById(R.id.tvCameraTitle)
            private val tvTypeBadge: TextView = itemView.findViewById(R.id.tvTypeBadge)
            private val tvDateTime: TextView = itemView.findViewById(R.id.tvDateTime)
            private val tvFileSize: TextView = itemView.findViewById(R.id.tvFileSize)
            private val btnShare: ImageButton = itemView.findViewById(R.id.btnShareItem)
            private val btnDelete: ImageButton = itemView.findViewById(R.id.btnDeleteItem)

            fun bind(item: MediaItem) {
                tvCameraTitle.text = item.cameraName
                val dateFmt = SimpleDateFormat("dd MMM yyyy, HH:mm:ss", Locale.getDefault())
                tvDateTime.text = dateFmt.format(Date(item.lastModified))
                tvFileSize.text = formatFileSize(item.sizeBytes)

                if (item.isVideo) {
                    tvTypeBadge.text = "REKAMAN"
                    tvTypeBadge.setTextColor(ContextCompat.getColor(itemView.context, R.color.status_warn))
                    ivPlayBadge.visibility = View.VISIBLE
                    ivThumb.setImageResource(R.drawable.ic_video)
                    ivThumb.scaleType = ImageView.ScaleType.CENTER_INSIDE
                } else {
                    tvTypeBadge.text = "FOTO"
                    tvTypeBadge.setTextColor(ContextCompat.getColor(itemView.context, R.color.status_ok))
                    ivPlayBadge.visibility = View.GONE
                    ivThumb.scaleType = ImageView.ScaleType.CENTER_CROP
                    loadThumbnail(ivThumb, item.file)
                }

                itemView.setOnClickListener { onItemClick?.invoke(item) }
                btnShare.setOnClickListener { onShareClick?.invoke(item) }
                btnDelete.setOnClickListener { onDeleteClick?.invoke(item) }
            }

            private fun loadThumbnail(target: ImageView, file: File) {
                target.tag = file.absolutePath
                target.setImageDrawable(null)
                (itemView.context as? AppCompatActivity)?.lifecycleScope?.launch {
                    val bitmap = withContext(Dispatchers.IO) {
                        decodeSampledBitmap(file, 160, 120)
                    }
                    if (target.tag == file.absolutePath && bitmap != null) {
                        target.setImageBitmap(bitmap)
                    }
                }
            }
        }
    }

    companion object {
        fun formatFileSize(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            if (bytes < 1024) return "$bytes B"
            val kb = bytes / 1024.0
            if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
            val mb = kb / 1024.0
            return String.format(Locale.US, "%.1f MB", mb)
        }

        fun decodeSampledBitmap(file: File, reqWidth: Int, reqHeight: Int): Bitmap? {
            return try {
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeFile(file.absolutePath, options)

                var inSampleSize = 1
                val (height: Int, width: Int) = options.outHeight to options.outWidth
                if (height > reqHeight || width > reqWidth) {
                    val halfHeight: Int = height / 2
                    val halfWidth: Int = width / 2
                    while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                        inSampleSize *= 2
                    }
                }

                val decodeOptions = BitmapFactory.Options().apply {
                    this.inSampleSize = inSampleSize
                }
                BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
            } catch (_: Throwable) {
                null
            }
        }
    }
}
