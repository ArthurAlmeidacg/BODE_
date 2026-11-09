/*
 * Copyright 2022 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *             http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.tensorflow.lite.examples.objectdetection

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.os.Build
import android.os.Vibrator
import android.os.VibratorManager
import android.os.VibrationEffect
import android.util.Log
import android.speech.tts.TextToSpeech
import java.util.Locale
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.Rot90Op
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.Detection
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import android.media.AudioAttributes
import android.media.SoundPool




class ObjectDetectorHelper(
    var threshold: Float = 0.5f,
    var numThreads: Int = 2,
    var maxResults: Int = 3,
    var currentDelegate: Int = 0,
    var currentModel: Int = 0,
    // Tamanho mínimo do bounding box (área da caixa / área da imagem) para que uma
    // detecção seja considerada válida (mostrada no overlay e anunciada por áudio/vibração).
    // Evita sinais para objetos muito distantes/pequenos na cena.
    var minBoxSizeRatio: Float = 0.15f,
    // Limites da "zona central", em fração da largura da imagem (0f a 1f).
    // Abaixo de centerZoneStartRatio = "left"; acima de centerZoneEndRatio = "right".
    var centerZoneStartRatio: Float = 0.33f,
    var centerZoneEndRatio: Float = 0.67f,
    val context: Context,
    val objectDetectorListener: DetectorListener?
)
{

    // Intervalo entre uma "vez" da fila e outra (ver pendingZonesQueue mais
    // abaixo). Compartilhado pelos 3 mecanismos para os dois modos terem a
    // mesma cadência na comparação do TCC — só a forma do feedback muda
    // (som/vibração vs. fala). Ajuste esse número à vontade.
    private val feedbackCooldownMs = 1500L

    // --- Fila de anúncio por zona (esquerda/centro/direita) ---
    // Em vez de só anunciar 1 objeto e ignorar o resto quando há vários na
    // tela ao mesmo tempo, cada zona ocupada entra numa fila e é anunciada na
    // sua vez, uma a cada feedbackCooldownMs. Se o objeto sair de cena antes
    // da vez da zona chegar, essa vez é descartada (não anuncia nada "velho").
    private val zoneOrder = listOf("left", "center", "right")
    private val pendingZonesQueue = ArrayDeque<String>()
    private val zoneLastAnnouncedAt = mutableMapOf<String, Long>()
    private var nextQueueProcessTime = 0L

    private lateinit var soundPool: SoundPool
    private var soundId: Int = 0
    private var soundIdLeft: Int = 0
    private var soundIdRight: Int = 0
    private var soundIdCenter: Int = 0
    private var soundReady = false

    // --- Vibração ---
    private lateinit var vibrator: Vibrator
    private val vibrationShortDurationMs = 100L // duração de cada pulso curto (objeto no centro)
    private val vibrationLongDurationMs = 400L  // duração de cada pulso longo (objeto na lateral)
    private val vibrationPauseMs = 150L         // pausa entre pulsos dentro do mesmo padrão

    // --- Fala (TTS) para o modo de feedback FeedbackMode.SPEECH ---
    // Modo usado na comparação do TCC: em vez de bipes (modo SOUND), fala o
    // rótulo do objeto + a posição na vez daquela zona, ex.: "person right".
    private var speechFeedback: TextToSpeech? = null
    private var speechFeedbackReady = false




    // For this example this needs to be a var so it can be reset on changes. If the ObjectDetector
    // will not change, a lazy val would be preferable.
    private var objectDetector: ObjectDetector? = null

    init {
        setupObjectDetector()

        soundPool = SoundPool.Builder()
            .setMaxStreams(1)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()

        soundIdLeft = soundPool.load(context, R.raw.object_left, 1)
        soundIdRight = soundPool.load(context, R.raw.object_right, 1)
        soundIdCenter = soundPool.load(context, R.raw.audio_cue, 1)

        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) { // Status 0 significa que o som foi carregado com sucesso
                soundReady = true
                Log.d("ObjectDetectorHelper", "Som $sampleId carregado com sucesso.")
            } else {
                Log.e("ObjectDetectorHelper", "Falha ao carregar som com ID: $sampleId")
            }
        }

        // Inicializa o Vibrator de acordo com a versão do Android
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager =
                context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        // Inicializa o TTS usado no modo de feedback SPEECH
        speechFeedback = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                speechFeedbackReady = true
                // Os rótulos do modelo (dataset COCO) e a palavra de posição são
                // falados em inglês (ex.: "person right"). Troque o Locale aqui
                // (ou traduza os rótulos em speakDetection) se quiser o feedback
                // em português/alemão.
                val result = speechFeedback?.setLanguage(Locale.US)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e("ObjectDetectorHelper", "Idioma indisponível no TTS de feedback falado")
                }
                speechFeedback?.setSpeechRate(1.1f) // um pouco mais rápido que o padrão
            } else {
                Log.e("ObjectDetectorHelper", "Falha ao inicializar TTS de feedback falado")
            }
        }
    }


    fun clearObjectDetector() {
        objectDetector = null
        soundPool.release()
        speechFeedback?.stop()
        speechFeedback?.shutdown()
        speechFeedback = null
        pendingZonesQueue.clear()
        zoneLastAnnouncedAt.clear()
    }

    // Initialize the object detector using current settings on the
    // thread that is using it. CPU and NNAPI delegates can be used with detectors
    // that are created on the main thread and used on a background thread, but
    // the GPU delegate needs to be used on the thread that initialized the detector
    fun setupObjectDetector() {
        // Create the base options for the detector using specifies max results and score threshold
        val optionsBuilder =
            ObjectDetector.ObjectDetectorOptions.builder()
                .setScoreThreshold(threshold)
                .setMaxResults(maxResults)

        // Set general detection options, including number of used threads
        val baseOptionsBuilder = BaseOptions.builder().setNumThreads(numThreads)

        // Use the specified hardware for running the model. Default to CPU
        when (currentDelegate) {
            DELEGATE_CPU -> {
                // Default
            }
            DELEGATE_GPU -> {
                if (CompatibilityList().isDelegateSupportedOnThisDevice) {
                    baseOptionsBuilder.useGpu()
                } else {
                    objectDetectorListener?.onError("GPU is not supported on this device")
                }
            }
            DELEGATE_NNAPI -> {
                baseOptionsBuilder.useNnapi()
            }
        }

        optionsBuilder.setBaseOptions(baseOptionsBuilder.build())

        val modelName =
            when (currentModel) {
                MODEL_MOBILENETV1 -> "mobilenetv1.tflite"
                MODEL_EFFICIENTDETV0 -> "efficientdet-lite0.tflite"
                MODEL_EFFICIENTDETV1 -> "efficientdet-lite1.tflite"
                MODEL_EFFICIENTDETV2 -> "efficientdet-lite2.tflite"
                else -> "mobilenetv1.tflite"
            }

        try {
            objectDetector =
                ObjectDetector.createFromFileAndOptions(context, modelName, optionsBuilder.build())
        } catch (e: IllegalStateException) {
            objectDetectorListener?.onError(
                "Object detector failed to initialize. See error logs for details"
            )
            Log.e("Test", "TFLite failed to load model with error: " + e.message)
        }
    }

    fun detect(image: Bitmap, imageRotation: Int) {
        if (objectDetector == null) {
            setupObjectDetector()
        }

        // Inference time is the difference between the system time at the start and finish of the
        // process
        var inferenceTime = SystemClock.uptimeMillis()

        // Create preprocessor for the image.
        // See https://www.tensorflow.org/lite/inference_with_metadata/
        //            lite_support#imageprocessor_architecture
        val imageProcessor =
            ImageProcessor.Builder()
                .add(Rot90Op(-imageRotation / 90))
                .build()

        // Preprocess the image and convert it into a TensorImage for detection.
        val tensorImage = imageProcessor.process(TensorImage.fromBitmap(image))

        val results = objectDetector?.detect(tensorImage)
        inferenceTime = SystemClock.uptimeMillis() - inferenceTime

        // 1) Filtra detecções muito pequenas (objetos distantes): só mantém as que
        // ocupam pelo menos "minBoxSizeRatio" da área total da imagem.
        val imageArea = (tensorImage.width * tensorImage.height).toFloat()
        val filteredResults = results?.filter { detection ->
            val box = detection.boundingBox
            val boxArea = box.width() * box.height()
            imageArea > 0f && (boxArea / imageArea) >= minBoxSizeRatio
        }?.toMutableList()

        objectDetectorListener?.onResults(
            filteredResults,
            inferenceTime,
            tensorImage.height,
            tensorImage.width)

        filteredResults?.let { detections ->
            // Enquanto isSuspended (durante o fluxo de foto + Gemini), não
            // enfileira nem anuncia nada, para não sobrepor a descrição falada.
            if (!DetectionFeedbackSettings.isSuspended) {
                // 2) Zona central mais estreita (padrão: 33%–67% da largura) faz o
                // "left"/"right" disparar mais cedo, assim que o objeto sai do centro.
                val positioned = detections.map { detection ->
                    val boundingBox = detection.boundingBox
                    val objectCenterX = (boundingBox.left + boundingBox.right) / 2
                    val objectCenterXRatio = objectCenterX / tensorImage.width.toFloat()
                    val position = when {
                        objectCenterXRatio < centerZoneStartRatio -> "left"
                        objectCenterXRatio > centerZoneEndRatio -> "right"
                        else -> "center"
                    }
                    Triple(detection, position, objectCenterXRatio)
                }

                // Coloca cada zona recém-ocupada na fila, e processa a "vez"
                // da fila se já deu o intervalo — funciona igual nos 2 modos
                // do teste do TCC, só muda o que toca em cada vez (som+
                // vibração no SOUND, fala+vibração no SPEECH).
                enqueueOccupiedZones(positioned)
                processZoneQueue(positioned)
            }
        }
    }

    /**
     * Coloca na fila (pendingZonesQueue) cada zona esquerda/centro/direita que
     * tem pelo menos um objeto detectado agora — na ordem left/center/right,
     * então se várias ficarem ocupadas no mesmo frame elas entram nessa ordem.
     * Não coloca de novo uma zona que já está na fila, nem uma que acabou de
     * ser anunciada há menos de feedbackCooldownMs.
     */
    private fun enqueueOccupiedZones(positioned: List<Triple<Detection, String, Float>>) {
        val now = System.currentTimeMillis()
        val occupiedZones = positioned.map { it.second }.toSet()

        for (zone in zoneOrder) {
            if (zone !in occupiedZones) continue
            val alreadyQueued = pendingZonesQueue.contains(zone)
            val cooledDown = now - (zoneLastAnnouncedAt[zone] ?: 0L) >= feedbackCooldownMs
            if (!alreadyQueued && cooledDown) {
                pendingZonesQueue.addLast(zone)
                Log.d("ObjectDetectorHelper", "Zona '$zone' entrou na fila")
            }
        }
    }

    /**
     * A cada feedbackCooldownMs, tira a próxima zona da fila e anuncia
     * (bipe ou fala, conforme o modo) + vibra para ela. Antes de anunciar,
     * reconfirma que a zona ainda está ocupada agora — se o objeto já saiu
     * de cena antes da vez dela, a zona é descartada sem tocar nada.
     */
    private fun processZoneQueue(positioned: List<Triple<Detection, String, Float>>) {
        val now = System.currentTimeMillis()
        if (now < nextQueueProcessTime) return
        val zone = pendingZonesQueue.removeFirstOrNull() ?: return

        nextQueueProcessTime = now + feedbackCooldownMs

        val stillPresent = positioned.firstOrNull { it.second == zone }
        if (stillPresent == null) {
            Log.d("ObjectDetectorHelper", "Zona '$zone' vazia na hora de anunciar, descartada")
            return
        }

        zoneLastAnnouncedAt[zone] = now

        when (DetectionFeedbackSettings.mode) {
            FeedbackMode.SOUND -> playSoundBasedOnPosition(zone)
            FeedbackMode.SPEECH -> speakDetection(stillPresent.first, zone)
        }
        triggerVibration(zone)
    }

    private fun playSoundBasedOnPosition(position: String) {
        // O espaçamento entre um som e outro agora é controlado pela fila
        // (processZoneQueue), então toca direto, sem cooldown próprio aqui.
        val soundToPlay = when (position) {
            "left" -> soundIdLeft // Som para esquerda
            "right" -> soundIdRight // Som para direita
            else -> soundIdCenter // Som para o centro
        }
        Log.d("ObjectDetectorHelper", "Tocando som com ID: $soundToPlay")
        soundPool.play(soundToPlay, 1f, 1f, 1, 0, 1f)
    }

    /**
     * Fala o rótulo + a posição do objeto cuja zona chegou a vez na fila,
     * ex.: "person right". O espaçamento entre uma fala e outra é controlado
     * pela fila (processZoneQueue/feedbackCooldownMs), não por um cooldown
     * próprio aqui. Usado apenas no modo FeedbackMode.SPEECH.
     */
    private fun speakDetection(detection: Detection, position: String) {
        val tts = speechFeedback ?: return
        if (!speechFeedbackReady) return

        val label = detection.categories.firstOrNull()?.label ?: return
        val utterance = "$label $position"

        // QUEUE_FLUSH interrompe uma fala anterior (se ainda estiver rolando)
        // para sempre anunciar a vez mais recente da fila.
        tts.speak(utterance, TextToSpeech.QUEUE_FLUSH, null, "detection_$position")
        Log.d("ObjectDetectorHelper", "Falando: $utterance")
    }

    /**
     * Aciona um padrão de vibração de acordo com a posição da zona cuja vez
     * chegou na fila (mesmo espaçamento do som/fala, controlado por
     * processZoneQueue — não tem cooldown próprio aqui):
     * - "left": 1 vibrações curta
     * - "right": 3 vibração curta
     * - "center" (ou qualquer outro valor): 2 vibrações curtas
     */
    private fun triggerVibration(position: String) {
        if (!::vibrator.isInitialized || !vibrator.hasVibrator()) return

        // Formato do padrão: [espera, vibra, espera, vibra, ...]

        val pattern = when (position) {
            "left" -> longArrayOf(0, vibrationShortDurationMs)
            "right" -> longArrayOf(0, vibrationShortDurationMs, vibrationPauseMs,vibrationShortDurationMs,vibrationPauseMs, vibrationShortDurationMs)
            else -> longArrayOf(0, vibrationShortDurationMs, vibrationPauseMs, vibrationShortDurationMs)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
        Log.d("ObjectDetectorHelper", "Vibração ($position) acionada")
    }

    interface DetectorListener {
        fun onError(error: String)
        fun onResults(
            results: MutableList<Detection>?,
            inferenceTime: Long,
            imageHeight: Int,
            imageWidth: Int
        )
    }

    companion object {
        const val DELEGATE_CPU = 0
        const val DELEGATE_GPU = 1
        const val DELEGATE_NNAPI = 2
        const val MODEL_MOBILENETV1 = 0
        const val MODEL_EFFICIENTDETV0 = 1
        const val MODEL_EFFICIENTDETV1 = 2
        const val MODEL_EFFICIENTDETV2 = 3
    }
}