package me.weishu.kernelsu.homework

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import me.weishu.kernelsu.R

/** 全屏相机：悬浮窗和主页都用它来拍照，可连拍多页后一次交给 AI。 */
class CameraActivity : ComponentActivity() {

    companion object {
        const val EXTRA_RESULT_RECEIVER = "homework.receiver"
        const val EXTRA_URIS = "homework.uris"
        private const val REQUEST_CAMERA = 1001
    }

    private lateinit var textureView: TextureView
    private lateinit var countView: TextView
    private lateinit var flashView: TextView
    private lateinit var captureView: TextView
    private lateinit var doneView: TextView
    private lateinit var cancelView: TextView

    private var controller: HomeworkCamera.Controller? = null
    private var receiver: ResultReceiver? = null
    private val uris = ArrayList<Uri>()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.homework_camera)

        textureView = findViewById(R.id.hw_camera_preview)
        countView = findViewById(R.id.hw_camera_count)
        flashView = findViewById(R.id.hw_flash)
        captureView = findViewById(R.id.hw_capture)
        doneView = findViewById(R.id.hw_done)
        cancelView = findViewById(R.id.hw_cancel)

        receiver = intent.getParcelableExtra(EXTRA_RESULT_RECEIVER)

        captureView.setOnClickListener { takeOne() }
        cancelView.setOnClickListener { finish() }
        doneView.setOnClickListener { finishWithUris() }
        flashView.setOnClickListener { toggleFlash() }

        textureView.setSurfaceTextureListener(object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: Surface, width: Int, height: Int) {
                startCamera()
            }

            override fun onSurfaceTextureSizeChanged(surface: Surface, width: Int, height: Int) {}

            override fun onSurfaceTextureDestroyed(surface: Surface): Boolean = false

            override fun onSurfaceTextureUpdated(surface: Surface) {}
        })

        if (!hasCameraPermission()) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA
            )
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CAMERA) return
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            toast("没有相机权限，拍不了")
            finish()
        }
    }

    private fun startCamera() {
        if (!hasCameraPermission()) return
        val c = controller ?: HomeworkCamera.Controller(
            this, HomeworkCamera.FakeLifecycleOwner(), textureView
        ).also { controller = it }
        if (c.isReady) return
        Thread {
            if (!c.start()) {
                mainHandler.post { toast("打不开相机，请检查权限") }
            }
        }.start()
    }

    private fun takeOne() {
        controller?.takePicture(
            onUri = { uri ->
                mainHandler.post {
                    if (uri != null) {
                        uris.add(uri)
                        countView.text = "已拍 ${uris.size} 张，点「完成」交给 AI"
                    }
                }
            },
            onError = { msg -> mainHandler.post { toast(msg) } },
        )
    }

    private fun toggleFlash() {
        val cm = getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
        val id = cm.cameraIdList.firstOrNull { it == CameraManager.CAMERA_ID_BACK }
            ?: cm.cameraIdList.firstOrNull() ?: return
        val next = !cm.getTorchMode(id)
        try {
            cm.setTorchMode(id, next)
        } catch (_: Exception) {
            toast("这个机型不支持闪光灯")
            return
        }
        flashView.text = if (next) "闪光灯开" else "闪光灯"
    }

    private fun finishWithUris() {
        if (uris.isEmpty()) {
            finish()
            return
        }
        val bundle = Bundle().apply {
            putParcelableArrayList(EXTRA_URIS, ArrayList(uris))
        }
        val r = receiver
        if (r != null) {
            r.send(RESULT_OK, bundle)
        } else {
            setResult(RESULT_OK, Intent().apply { putExtras(bundle) })
        }
        finish()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        controller?.stop()
        controller = null
        super.onDestroy()
    }
}
