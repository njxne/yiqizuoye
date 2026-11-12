package me.weishu.kernelsu.homework

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** CameraX 封装：悬浮窗（Service）和相机 Activity 共用一套逻辑。 */
object HomeworkCamera {

    /**
     * 手动驱动的 LifecycleOwner。
     *
     * CameraX 的 `bindToLifecycle` 要求宿主至少处于 STARTED 状态，而后台 Service 没有
     * 生命周期可绑，Activity 的生命周期在视图就绪时也可能还没到 STARTED。所以自己造一个，
     * 并在 [Controller.start] 里推到 RESUMED。
     */
    class FakeLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)

        override val lifecycle: Lifecycle
            get() = registry

        fun resume() {
            if (registry.currentState != Lifecycle.State.RESUMED) {
                registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            }
        }

        fun destroy() {
            if (registry.currentState != Lifecycle.State.DESTROYED) {
                registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            }
        }
    }

    class Controller(
        private val context: Context,
        private val previewView: PreviewView,
    ) {
        private val owner = FakeLifecycleOwner()
        private val executor: ExecutorService = Executors.newSingleThreadExecutor()
        private var provider: ProcessCameraProvider? = null
        @Volatile
        var imageCapture: ImageCapture? = null
            private set
        @Volatile
        var isReady: Boolean = false
            private set

        fun start(): Boolean {
            if (isReady) return true
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val capture = ImageCapture.Builder()
                .setTargetResolution(Size(2048, 2048))
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            imageCapture = capture

            return try {
                owner.resume()
                val cam = ProcessCameraProvider.getInstance(context).get()
                provider = cam
                cam.unbindAll()
                cam.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                isReady = true
                true
            } catch (e: Exception) {
                isReady = false
                false
            }
        }

        fun stop() {
            runCatching { provider?.unbindAll() }
            provider = null
            imageCapture = null
            isReady = false
            owner.destroy()
            executor.shutdownNow()
        }

        /**
         * 拍照，结果通过 [onUri] 返回（content Uri），失败走 [onError]。
         *
         * 落盘到 MediaStore 而不是 cacheDir，这样取到的 content Uri 可以跨进程传给
         * [FloatingWindowService]，Service 里再自己解码。
         */
        fun takePicture(onUri: (Uri?) -> Unit, onError: (String) -> Unit) {
            val cap = imageCapture
            if (cap == null || !isReady) {
                onError("相机还没准备好")
                return
            }
            val name = "hw_${System.currentTimeMillis()}.jpg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            }
            val options = ImageCapture.OutputFileOptions.Builder(
                context.contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values,
            ).build()
            cap.takePicture(
                options,
                executor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        onUri(output.savedUri)
                    }

                    override fun onError(exc: ImageCaptureException) {
                        onError(exc.message ?: "拍照失败")
                    }
                },
            )
        }
    }
}
