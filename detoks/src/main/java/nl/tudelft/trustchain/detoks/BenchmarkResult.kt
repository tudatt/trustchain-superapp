package nl.tudelft.trustchain.detoks

import com.github.mikephil.charting.data.Entry

data class BenchmarkResult (
    var timePerBlock : ArrayList<Entry>,
    var totalTime : Long,
    var payloadBandwith : Double,
    var lostPackets: Long
    )
