package com.wheelanalyzer.app

/**
 * Compares the six visible history slots. Slot 0 is the newest (leftmost).
 * The image-recognition layer supplies a label and confidence for each slot.
 * This class deliberately refuses to record uncertain or ambiguous shifts.
 */
enum class FoodSymbol {
    APPLE, GREENS, ORANGE, FISH, WATERMELON, CHICKEN, SHRIMP, BURGER, TURKEY, UNKNOWN
}

data class RecognizedSlot(val symbol: FoodSymbol, val confidence: Float)
data class HistorySnapshot(val slots: List<RecognizedSlot>) {
    init { require(slots.size == 6) { "History must contain exactly six slots" } }
}

data class NewResult(val symbol: FoodSymbol, val confidence: Float, val sequence: List<FoodSymbol>)

enum class UpdateStatus { INITIALIZED, NO_CHANGE, NEW_RESULT, AMBIGUOUS }
data class UpdateResult(val status: UpdateStatus, val result: NewResult? = null, val message: String)

class ResultSequenceAnalyzer(
    private val minConfidence: Float = 0.82f,
    private val maxMismatches: Int = 0
) {
    private var previous: HistorySnapshot? = null
    private val recorded = mutableListOf<FoodSymbol>()

    @Synchronized
    fun accept(current: HistorySnapshot): UpdateResult {
        val old = previous
        if (old == null) {
            previous = current
            return UpdateResult(UpdateStatus.INITIALIZED, message = "İlk geçmiş görüntüsü kaydedildi")
        }
        if (current.slots.any { it.symbol == FoodSymbol.UNKNOWN || it.confidence < minConfidence }) {
            return UpdateResult(UpdateStatus.AMBIGUOUS, message = "Bir veya daha fazla simge güvenle tanınamadı")
        }
        if (old.slots.any { it.symbol == FoodSymbol.UNKNOWN || it.confidence < minConfidence }) {
            previous = current
            return UpdateResult(UpdateStatus.AMBIGUOUS, message = "Önceki görüntü güvenilir değildi; referans yenilendi")
        }

        val oldSymbols = old.slots.map { it.symbol }
        val newSymbols = current.slots.map { it.symbol }
        if (oldSymbols == newSymbols) {
            return UpdateResult(UpdateStatus.NO_CHANGE, message = "Yeni sonuç yok")
        }

        // Expected game behavior: new symbol enters at the left; old slots shift right.
        val shiftMatches = (1..5).count { newSymbols[it] == oldSymbols[it - 1] }
        val mismatches = 5 - shiftMatches
        if (mismatches > maxMismatches) {
            return UpdateResult(UpdateStatus.AMBIGUOUS, message = "Geçmiş kayması doğrulanamadı; otomatik kayıt yapılmadı")
        }

        val newest = current.slots[0]
        recorded.add(newest.symbol)
        previous = current
        return UpdateResult(
            UpdateStatus.NEW_RESULT,
            NewResult(newest.symbol, newest.confidence, newSymbols),
            "Yeni sonuç: ${newest.symbol}"
        )
    }

    @Synchronized fun history(): List<FoodSymbol> = recorded.toList()
    @Synchronized fun reset() { previous = null; recorded.clear() }
}
