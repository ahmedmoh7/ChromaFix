package com.chromafix.app

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.*
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {
    private val pickVideo = 1001
    private var selectedUri: Uri? = null
    private var running = false
    private var sessionId: Long? = null
    private val cancelled = AtomicBoolean(false)
    private lateinit var fileLabel: TextView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var startButton: Button
    private lateinit var cancelButton: Button
    private lateinit var spatial: SeekBar
    private lateinit var temporal: SeekBar
    private lateinit var spatialValue: TextView
    private lateinit var temporalValue: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
            setBackgroundColor(0xFF101318.toInt())
        }
        scroll.addView(page)
        fun label(value: String, size: Float, color: Int = 0xFFFFFFFF.toInt(), bold: Boolean = false) =
            TextView(this).apply {
                text = value; textSize = size; setTextColor(color)
                if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
            }
        page.addView(label("ChromaFix", 30f, 0xFF78D6C6.toInt(), true))
        page.addView(label("4K VIDEO CHROMA NOISE REDUCTION", 11f, 0xFF9BA6B2.toInt()))
        spacer(page, 22)
        page.addView(Button(this).apply {
            text = "اختيار فيديو MOV / MP4"
            setOnClickListener {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "video/*"
                }, pickVideo)
            }
        }, params())
        fileLabel = label("لم يتم اختيار فيديو", 14f, 0xFFD9E1E8.toInt())
        page.addView(fileLabel)
        spacer(page, 18)
        page.addView(label("قوة إزالة النويز اللوني", 18f, -1, true))
        page.addView(label("Spatial: تنظيف النقط داخل الفريم", 13f, 0xFF9BA6B2.toInt()))
        spatialValue = label("24", 16f, 0xFF78D6C6.toInt(), true); page.addView(spatialValue)
        spatial = SeekBar(this).apply { max = 40; progress = 24 }; page.addView(spatial, params())
        page.addView(label("Temporal: تنظيف النقط المتحركة بين الفريمات", 13f, 0xFF9BA6B2.toInt()))
        temporalValue = label("36", 16f, 0xFF78D6C6.toInt(), true); page.addView(temporalValue)
        temporal = SeekBar(this).apply { max = 60; progress = 36 }; page.addView(temporal, params())
        spatial.setOnSeekBarChangeListener(listener { spatialValue.text = it.toString() })
        temporal.setOnSeekBarChangeListener(listener { temporalValue.text = it.toString() })
        page.addView(label("Luma ثابت = 2:2 للحفاظ على تفاصيل الإضاءة", 12f, 0xFF9BA6B2.toInt()))
        page.addView(label("تهيئة البداية: 90 فريم (تجريبية)", 12f, 0xFF9BA6B2.toInt()))
        spacer(page, 20)
        startButton = Button(this).apply { text = "بدء المعالجة"; setOnClickListener { startProcessing() } }
        page.addView(startButton, params())
        cancelButton = Button(this).apply {
            text = "إيقاف المعالجة"; isEnabled = false
            setOnClickListener {
                cancelled.set(true); sessionId?.let { FFmpegKit.cancel(it) }
                status.text = "جارٍ إيقاف المعالجة..."
            }
        }
        page.addView(cancelButton, params())
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        page.addView(progress, LinearLayout.LayoutParams(-1, dp(12)))
        status = label("جاهز. اختر فيديو للبدء.", 13f, 0xFFD9E1E8.toInt()); page.addView(status)
        spacer(page, 16)
        page.addView(label("التصدير MP4/H.264 بصيغة 8-bit yuv420p للتوافق. المعالجة محلية وقد تكون بطيئة مع فيديوهات 4K.", 12f, 0xFF9BA6B2.toInt()))
        setContentView(scroll)
    }

    private fun listener(update: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
            update(value.coerceAtLeast(1)); if (bar != null && bar.progress == 0) bar.progress = 1
        }
        override fun onStartTrackingTouch(bar: SeekBar?) {}
        override fun onStopTrackingTouch(bar: SeekBar?) {}
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == pickVideo && resultCode == RESULT_OK) {
            selectedUri = data?.data
            fileLabel.text = "تم اختيار: ${selectedUri?.lastPathSegment ?: "فيديو"}"
        }
    }

    private fun startProcessing() {
        val uri = selectedUri
        if (uri == null) { Toast.makeText(this, "اختار فيديو الأول", Toast.LENGTH_SHORT).show(); return }
        if (running) return
        running = true; cancelled.set(false); startButton.isEnabled = false
        cancelButton.isEnabled = true; progress.progress = 0; status.text = "جاري تجهيز الفيديو..."
        Thread {
            var input: File? = null
            var output: File? = null
            try {
                input = File(cacheDir, "chromafix_input_${System.currentTimeMillis()}.mov")
                contentResolver.openInputStream(uri).use { stream ->
                    requireNotNull(stream) { "تعذر فتح الفيديو" }
                    FileOutputStream(input).use { stream.copyTo(it) }
                }
                output = File(cacheDir, "chromafix_output_${System.currentTimeMillis()}.mp4")
                val cs = spatial.progress.coerceAtLeast(1)
                val ct = temporal.progress.coerceAtLeast(1)
                val filter = "loop=loop=90:size=1:start=0,hqdn3d=2:$cs:2:$ct,trim=start_frame=90,setpts=PTS-STARTPTS,format=yuv420p"
                val command = "-y -i ${quote(input.absolutePath)} -vf ${quote(filter)} -c:v libx264 -crf 18 -preset ultrafast -threads 4 -c:a aac -b:a 192k -movflags +faststart ${quote(output.absolutePath)}"
                runOnUiThread { status.text = "جاري إزالة النويز... Extreme $cs:$ct" }
                val session = FFmpegKit.execute(command)
                sessionId = session.sessionId
                if (cancelled.get()) throw InterruptedException("تم إيقاف المعالجة")
                if (!ReturnCode.isSuccess(session.returnCode)) throw IllegalStateException(session.failStackTrace ?: "FFmpeg failed")
                saveToDownloads(output, "ChromaFix_${System.currentTimeMillis()}.mp4")
                runOnUiThread {
                    progress.progress = 100
                    status.text = "اكتملت المعالجة. الحفظ في Downloads/ChromaFix"
                    Toast.makeText(this, "تم حفظ الفيديو في Downloads", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = if (cancelled.get()) "تم إيقاف المعالجة." else "خطأ: ${e.message}" }
            } finally {
                input?.delete(); output?.delete()
                runOnUiThread {
                    running = false; startButton.isEnabled = true; cancelButton.isEnabled = false
                    if (progress.progress < 100 && !cancelled.get()) progress.progress = 0
                }
            }
        }.start()
    }

    private fun saveToDownloads(source: File, name: String) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ChromaFix")
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("تعذر إنشاء ملف في Downloads")
        contentResolver.openOutputStream(uri).use { out ->
            requireNotNull(out); FileInputStream(source).use { it.copyTo(out) }
        }
    }

    private fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun params() = LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT)
    private fun spacer(parent: LinearLayout, h: Int) { parent.addView(View(this), LinearLayout.LayoutParams(1, dp(h))) }
}