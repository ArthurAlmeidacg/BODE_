package org.tensorflow.lite.examples.objectdetection

import org.tensorflow.lite.examples.objectdetection.BuildConfig
import android.app.Activity
import android.content.Intent
import android.provider.MediaStore
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
//import com.google.ai.client.generativeai.BuildConfig
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.launch
import org.tensorflow.lite.examples.objectdetection.databinding.ActivityMainBinding
import android.speech.tts.TextToSpeech
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var activityMainBinding: ActivityMainBinding
    private var clickCount = 0
    private val clickTimeout = 1000L // Intervalo máximo entre cliques (1 segundo)
    private lateinit var generativeModel: GenerativeModel
    private lateinit var textToSpeech: TextToSpeech

    companion object {
        private const val REQUEST_IMAGE_CAPTURE = 1
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activityMainBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(activityMainBinding.root)

        textToSpeech = TextToSpeech(this, this)

        // Configurar o modelo Gemini
        generativeModel = GenerativeModel(
            modelName = "gemini-1.5-flash",
            apiKey = BuildConfig.API_KEY
        )

        // Detectar toques na tela
        activityMainBinding.root.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                handleTouch()
            }
            true
        }
    }

    private fun handleTouch() {
        clickCount++
        activityMainBinding.root.removeCallbacks(clearClickCount)
        activityMainBinding.root.postDelayed(clearClickCount, clickTimeout)

        if (clickCount == 3) {
            clickCount = 0
            openCamera()
        }
    }

    private val clearClickCount = Runnable { clickCount = 0 }

    private fun openCamera() {
        val takePictureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (takePictureIntent.resolveActivity(packageManager) != null) {
            startActivityForResult(takePictureIntent, REQUEST_IMAGE_CAPTURE)
        } else {
            Log.e("CameraIntent", "Não foi possível abrir a câmera.")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_IMAGE_CAPTURE && resultCode == Activity.RESULT_OK) {
            val capturedImage = data?.extras?.get("data") as? Bitmap
            if (capturedImage != null) {
                sendImageToGemini(capturedImage)
            } else {
                Log.e("CameraResult", "Imagem capturada está nula.")
            }
        }
    }

    private fun sendImageToGemini(image: Bitmap) {

        // Lançar uma coroutine para chamar a função `suspend`
        lifecycleScope.launch {
            try {
                val inputContent = content {
                    image(image)
                    text("Descreva esta imagem.")
                }

                val response = generativeModel.generateContent(inputContent)

                // Tratar o valor nullable de `response.text`
                val responseText = response.text ?: "Nenhuma descrição retornada."
                Log.d("GeminiResponse", responseText)
                speakText(responseText)
            } catch (e: Exception) {
                Log.e("GeminiError", "Erro ao gerar conteúdo: ${e.message}")
            }
        }
    }


    private fun speakText(text: String) {
        textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            // Configurar idioma para português do Brasil (ou outro idioma)
            textToSpeech.language = Locale.getDefault()
        } else {
            Log.e("TTS", "Falha ao inicializar o TextToSpeech.")
        }
    }

    override fun onDestroy() {
        // Liberar recursos do TextToSpeech ao destruir a atividade
        if (::textToSpeech.isInitialized) {
            textToSpeech.stop()
            textToSpeech.shutdown()
        }
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
            finishAfterTransition()
        } else {
            super.onBackPressed()
        }
    }
}
