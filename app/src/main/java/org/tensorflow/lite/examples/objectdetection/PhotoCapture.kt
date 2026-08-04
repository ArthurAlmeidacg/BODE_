package org.tensorflow.lite.examples.objectdetection

import android.content.Context
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.Toast
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class PhotoCapture(private val context: Context, private val activity: AppCompatActivity) {

    private var clickCount = 0
    private lateinit var gestureDetector: GestureDetector

    init {
        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                clickCount++
                if (clickCount == 3) {
                    // Quando 3 cliques forem detectados, captura a foto
                    capturePhoto()
                    clickCount = 0  // Resetar o contador
                }
                return super.onSingleTapConfirmed(e)
            }
        })
    }

    fun onTouchEvent(event: MotionEvent): Boolean {
        return gestureDetector.onTouchEvent(event)
    }

    private fun capturePhoto() {
        // Dispara a ação de captura de foto
        Toast.makeText(context, "Foto capturada", Toast.LENGTH_SHORT).show()

        // Aqui você pode adicionar a lógica de tirar uma foto usando a câmera
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        activity.startActivityForResult(intent, 1)  // 1 é o código de requisição para captura de foto
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 1 && resultCode == AppCompatActivity.RESULT_OK) {
            val imageUri: Uri? = data?.data
            // Aqui você pode obter o caminho da imagem e fazer o upload para o Gemini
            if (imageUri != null) {
                sendToGemini(imageUri)
            }
        }
    }

    private fun sendToGemini(imageUri: Uri) {
        // Envie a imagem para o Gemini aqui
        Toast.makeText(context, "Imagem enviada para Gemini", Toast.LENGTH_SHORT).show()

        // Após a resposta, você pode chamar a função de TTS para ler a descrição
        speakDescription("Esta é uma descrição fictícia da imagem.")
    }

    private fun speakDescription(description: String) {
        // Implemente a lógica de TTS (Text-to-Speech) aqui
        Toast.makeText(context, description, Toast.LENGTH_LONG).show()
    }
}
