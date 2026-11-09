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
import android.view.GestureDetector
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.tensorflow.lite.examples.objectdetection.databinding.ActivityMainBinding
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var activityMainBinding: ActivityMainBinding
    private var clickCount = 0
    private val clickTimeout = 1500L // Intervalo máximo entre cliques (1 segundo)
    // Tempo mínimo de toque contínuo para alternar o modo de feedback (som/fala).
    // Bem maior que um long-press comum (~500ms) de propósito, para não trocar
    // o modo sem querer ao segurar o celular ou ajustar a pega.
    private val longPressDurationMs = 3000L
    private lateinit var textToSpeech: TextToSpeech
    private lateinit var gestureDetector: GestureDetector

    companion object {
        private const val REQUEST_IMAGE_CAPTURE = 1
        // Mesmo utteranceId que speakText() já usa para a descrição/erro do
        // Gemini. Serve para saber, via onDone/onError, quando essa fala
        // termina e o feedback de detecção pode voltar a soar.
        private const val GEMINI_DESCRIPTION_UTTERANCE_ID = "gemini_description"
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activityMainBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(activityMainBinding.root)

        textToSpeech = TextToSpeech(this, this)

        // Configurar o modelo Gemini
        //generativeModel = GenerativeModel(
           // modelName = "gemini-flash-latest",
            //apiKey = BuildConfig.API_KEY
        //)

        // Detecta toques na tela inteira:
        // - 3 toques rápidos -> tira foto e descreve com o Gemini (já existia)
        // - toque contínuo por longPressDurationMs (3s) -> alterna o modo de
        //   feedback da detecção em tempo real entre SOUND (bipes) e SPEECH
        //   (fala "objeto posição"), usado na comparação do TCC.
        // onSingleTapUp cuida da contagem de toques. Não uso o onLongPress
        // padrão do GestureDetector aqui porque ele dispara em ~500ms (fácil
        // de acionar sem querer) e esse tempo não é configurável nele; em vez
        // disso, o touch listener abaixo agenda handleLongPress() para rodar
        // só depois de longPressDurationMs de toque contínuo, cancelando o
        // agendamento se o dedo for solto (ou o toque for cancelado) antes disso.
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                handleTap()
                return true
            }
        })

        activityMainBinding.root.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    activityMainBinding.root.postDelayed(toggleModeRunnable, longPressDurationMs)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    activityMainBinding.root.removeCallbacks(toggleModeRunnable)
                }
            }
            true
        }
    }

    fun getDeviceLanguage(): String {
        return Locale.getDefault().toLanguageTag()
    }

    private fun handleTap() {
        clickCount++
        activityMainBinding.root.removeCallbacks(clearClickCount)
        activityMainBinding.root.postDelayed(clearClickCount, clickTimeout)

        if (clickCount == 3) {
            clickCount = 0
            openCamera()
        }
    }

    private val clearClickCount = Runnable { clickCount = 0 }
    private val toggleModeRunnable = Runnable { handleLongPress() }

    private fun handleLongPress() {
        // Zera a contagem de toques para a pressão longa nunca ser contada
        // como um dos 3 cliques que abrem a câmera/Gemini.
        clickCount = 0
        activityMainBinding.root.removeCallbacks(clearClickCount)

        val newMode = DetectionFeedbackSettings.toggle()
        val message = when (newMode) {
            FeedbackMode.SOUND -> "Modo som ativado"
            FeedbackMode.SPEECH -> "Modo fala ativado"
        }
        Log.d("MainActivity", "Feedback de detecção alternado para: $newMode")
        speakText(message)
    }

    private fun openCamera() {
        val takePictureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (takePictureIntent.resolveActivity(packageManager) != null) {
            // Suspende o feedback contínuo (som/fala) enquanto o usuário tira
            // a foto e aguarda a resposta do Gemini, para as duas falas não
            // se sobreporem.
            DetectionFeedbackSettings.isSuspended = true
            startActivityForResult(takePictureIntent, REQUEST_IMAGE_CAPTURE)
        } else {
            Log.e("CameraIntent", "Não foi possível abrir a câmera.")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_IMAGE_CAPTURE) {
            if (resultCode == Activity.RESULT_OK) {
                val capturedImage = data?.extras?.get("data") as? Bitmap
                if (capturedImage != null) {
                    sendImageToGemini(capturedImage)
                } else {
                    Log.e("CameraResult", "Imagem capturada está nula.")
                    DetectionFeedbackSettings.isSuspended = false
                }
            } else {
                // Usuário cancelou a captura da foto
                DetectionFeedbackSettings.isSuspended = false
            }
        }
    }

    private fun sendImageToGemini(image: Bitmap) {

        // Lançar uma coroutine para chamar a função suspend
        lifecycleScope.launch {
            try {

                // Detectar o idioma do celular
                val deviceLanguage = getDeviceLanguage()

                val prompt = """
                    You are a visual assistant for a blind or visually impaired user. Describe the image clearly, naturally, and concisely for text-to-speech (TTS).
    
                    CRITICAL RULES:
    - Respond strictly in the device language ($deviceLanguage). Never mix languages, output language codes, or mention these instructions.
    - Start directly with the description. No intro ("Here is..."), no AI mentions.
    - NO Markdown, bullet points, lists, emojis, or special formatting. Use short, natural sentences (normally 3 to 6 sentences).
    
    CONTENT GUIDELINES:
    1. Environment: Briefly summarize the place type (e.g., room, street) and surroundings.
    2. Objects & Space: Mention key objects and their relative positions (left, right, in front, distance).
    3. People: Note the number of people and visible actions (standing, walking) without guessing identities or emotions.
    4. Safety & Hazards: Prioritize obstacles and hazards in the walking path (stairs, poles, vehicles, holes). Never invent hazards or claim a path is safe.
    5. Text: Read legible signs or text; state if unclear.
    6. Uncertainty: Never invent details. Mention if the image is blurry, dark, or unclear.
    
    Prioritize what is most relevant, actionable, and useful for the user's safety and orientation.
            """.trimIndent()


                // Tratar o valor nullable de response.text
                val responseText = OpenRouterClient
                    .describeImage(BuildConfig.API_KEY, prompt, image)
                    .ifBlank { "Nenhuma descrição retornada." }

                Log.d("GeminiResponse", responseText)
                speakText(responseText)


            } catch (e: Exception) {

                Log.e(
                    "GeminiError",
                    "Erro ao gerar conteúdo: ${e.message}",
                    e
                )
                speakError()
            }
        }
    }


    private fun speakText(text: String) {
        if (::textToSpeech.isInitialized) {
            textToSpeech.speak(
                text,
                TextToSpeech.QUEUE_FLUSH,
                null,
                GEMINI_DESCRIPTION_UTTERANCE_ID
            )
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {

            val deviceLocale = Locale.getDefault()

            val result = textToSpeech.setLanguage(deviceLocale)

            if (result == TextToSpeech.LANG_MISSING_DATA ||
                result == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                Log.e(
                    "TTS",
                    "O idioma ${deviceLocale.toLanguageTag()} não está disponível no TTS."
                )
            } else {
                Log.d(
                    "TTS",
                    "TTS configurado para: ${deviceLocale.toLanguageTag()}"
                )
            }

            // speakText() (usada tanto para a descrição de sucesso quanto para
            // speakError()) sempre marca a fala com GEMINI_DESCRIPTION_UTTERANCE_ID.
            // Esse listener escuta quando essa fala especificamente termina, para
            // só então liberar o feedback contínuo de detecção (som/fala).
            textToSpeech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}

                override fun onDone(utteranceId: String?) {
                    if (utteranceId == GEMINI_DESCRIPTION_UTTERANCE_ID) {
                        DetectionFeedbackSettings.isSuspended = false
                    }
                }

                override fun onError(utteranceId: String?) {
                    if (utteranceId == GEMINI_DESCRIPTION_UTTERANCE_ID) {
                        DetectionFeedbackSettings.isSuspended = false
                    }
                }
            })

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
    private fun speakError() {

        val language = Locale.getDefault().language

        val message = when (language) {

            "pt" -> {
                "Desculpe, ocorreu um erro ao tentar analisar a imagem. Por favor, tente novamente."
            }

            "en" -> {
                "Sorry, an error occurred while analyzing the image. Please try again."
            }

            "de" -> {
                "Entschuldigung, bei der Analyse des Bildes ist ein Fehler aufgetreten. Bitte versuchen Sie es erneut."
            }

            "es" -> {
                "Lo siento, ocurrió un error al analizar la imagen. Por favor, inténtelo de nuevo."
            }

            "fr" -> {
                "Désolé, une erreur s'est produite lors de l'analyse de l'image. Veuillez réessayer."
            }

            "it" -> {
                "Spiacente, si è verificato un errore durante l'analisi dell'immagine. Riprova."
            }

            else -> {
                "Sorry, an error occurred while analyzing the image. Please try again."
            }
        }

        speakText(message)
    }
}