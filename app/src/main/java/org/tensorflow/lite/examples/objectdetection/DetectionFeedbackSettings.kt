package org.tensorflow.lite.examples.objectdetection


enum class FeedbackMode {
    SOUND,
    SPEECH
}


object DetectionFeedbackSettings {

    @Volatile
    var mode: FeedbackMode = FeedbackMode.SOUND
        private set

    @Volatile
    var isSuspended: Boolean = false

    /** Alterna entre SOUND e SPEECH e retorna o novo modo. */
    fun toggle(): FeedbackMode {
        mode = if (mode == FeedbackMode.SOUND) FeedbackMode.SPEECH else FeedbackMode.SOUND
        return mode
    }
}
