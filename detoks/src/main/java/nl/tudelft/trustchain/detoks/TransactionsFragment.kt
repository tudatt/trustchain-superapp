package nl.tudelft.trustchain.detoks

import android.app.AlertDialog
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.mikephil.charting.animation.Easing
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet
import kotlinx.android.synthetic.main.test_fragment_layout.*
import nl.tudelft.ipv8.*
import nl.tudelft.ipv8.android.IPv8Android
import nl.tudelft.ipv8.util.toHex
import nl.tudelft.trustchain.common.ui.BaseFragment
import java.math.RoundingMode
import java.text.DecimalFormat
import kotlin.collections.ArrayList

class TransactionsFragment: BaseFragment(R.layout.test_fragment_layout) {
    private var peers: ArrayList<PeerViewModel> = arrayListOf()
    private lateinit var deToksCommunity: DeToksCommunity

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        deToksCommunity = IPv8Android.getInstance().getOverlay<DeToksCommunity>()!!
        val pkTextView = publicKeyTextView
        pkTextView.text = "My public key is: " + deToksCommunity.myPeer.key.pub().keyToBin().toHex()

        val benchmarkDialogButton = view.findViewById<Button>(R.id.benchmark_window_button)
        benchmarkDialogButton.setOnClickListener {
            showBenchmarkDialog()
        }

        val peerRecyclerView = peerListView
        peerRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        val adapter = PeerAdapter(peers)
        peerRecyclerView.adapter = adapter

        Thread {
            val deToksCommunity = IPv8Android.getInstance().getOverlay<DeToksCommunity>()
            if (deToksCommunity != null) {
                println("Community ServiceID: " + deToksCommunity.serviceId)
                while (true) {
                    Log.d("Detoks",  "Getting peers...", )
                    val peerList: List<Peer> = deToksCommunity.getPeers()

                    requireActivity().runOnUiThread {
                        for (peer in peerList) {
                            if (!peers.contains(PeerViewModel(peer.publicKey.toString(), peer))) {
                                peers.add(PeerViewModel(peer.publicKey.toString(), peer))
                                adapter.notifyItemInserted(peers.size - 1)
                            }

                        }

                        for (index in 0..peers.size-1) {
                            if (!peerList.contains(peers[index].peer)) {
                                peers.removeAt(index)
                                adapter.notifyItemRemoved(index)
                            }
                        }
                    }
                    Thread.sleep(500)
                }
            }
        }.start()
    }

    private fun specificBenchmark(builder: AlertDialog, benchmarkResultView: View, engineBenchmark: TransactionEngineBenchmark) {
        builder.setContentView(benchmarkResultView)
        builder.show()
        val benchmarkTypeTextView = benchmarkResultView.findViewById<TextView>(R.id.benchmarkTypeTextView)
        benchmarkTypeTextView.text = "unencrypted same content"
        val durationCountEditText = benchmarkResultView.findViewById<EditText>(R.id.benchmarkCountDurationEditText)

        val timeRadioButton = benchmarkResultView.findViewById<RadioButton>(R.id.timeRadioButton)
        val blocksRadioButton = benchmarkResultView.findViewById<RadioButton>(R.id.blocksRadioButton)

        blocksRadioButton.isChecked = true
        val graphResolutionEditText = benchmarkResultView.findViewById<EditText>(R.id.graphResolutionEditText)
        val runBenchmarkButton = benchmarkResultView.findViewById<Button>(R.id.runBenchmarkButton)

        val totalTimeTextView = benchmarkResultView.findViewById<TextView>(R.id.totalTimeValueTextView)
        val bandwithTextView = benchmarkResultView.findViewById<TextView>(R.id.bandwithValueTextView)

        val constraintLayout = benchmarkResultView.findViewById<ConstraintLayout>(R.id.resultConstraintLayout)

        constraintLayout.post(Runnable {
            builder.window?.clearFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
            )
        })

        val lineChart = benchmarkResultView.findViewById<LineChart>(R.id.benchmarkLineChart)
        lineChart.setTouchEnabled(true)
        lineChart.setPinchZoom(true)

        lineChart.description.text = "Days"
        lineChart.setNoDataText("No forex yet!")

        runBenchmarkButton.setOnClickListener {
            var resolution = graphResolutionEditText.text.toString()
            if (resolution == "") {
                resolution = "10"
            }

            var blocksOrTime = durationCountEditText.text.toString()
            if (blocksOrTime == "") {
                blocksOrTime = if (timeRadioButton.isChecked) {
                    "1"
                } else {
                    "1000"
                }
            }

            val result = engineBenchmark.unencryptedBasicSameContent(
                Integer.parseInt(resolution),
                Integer.parseInt(blocksOrTime),
                timeRadioButton.isChecked
            )

            val df = DecimalFormat("#.##")
            df.roundingMode = RoundingMode.DOWN
            val roundoff = df.format(result.payloadBandwith)
            totalTimeTextView.text = (result.totalTime / 1000000).toDouble().toString()
            bandwithTextView.text = roundoff

            val dataset = LineDataSet(result.timePerBlock, "time per block")
            dataset.setDrawValues(false)
            dataset.lineWidth = 3f
            lineChart.data = LineData(dataset)

            lineChart.animateX(1800, Easing.EaseInExpo)

        }
    }

    private fun runComparisonBenchmark(builder: AlertDialog, engineBenchmark: TransactionEngineBenchmark) {
        val benchmarkComparison : View = layoutInflater.inflate(R.layout.nonipv8_benchmark_comparison, null)
        builder.setContentView(benchmarkComparison)
        builder.show()

        val chart = builder.findViewById<LineChart>(R.id.benchmarkLineChart)
        chart.setTouchEnabled(true)
        chart.setPinchZoom(true)
        chart.setNoDataText("Waiting for benchmark result")

        var allLineData : ArrayList<ILineDataSet> = ArrayList()

        val blockCountList : ArrayList<Int> = arrayListOf(1000, 2000, 5000, 10000, 15000, 20000, 25000)

        val unencryptedBasicSameContentValues : ArrayList<Entry> = ArrayList()
        val unencryptedBasicRandomValues : ArrayList<Entry> = ArrayList()
        val unencryptedBasicRandomSignedValues : ArrayList<Entry> = ArrayList()
        val unencryptedBasicRandomTrustchainValues : ArrayList<Entry> = ArrayList()

        val startComparisonButton = benchmarkComparison.findViewById<Button>(R.id.startComparisonButton)

        startComparisonButton.setOnClickListener {
            for (i in 0 until blockCountList.size) {
                println("benchmarking " + i)
                val result = engineBenchmark.unencryptedBasicSameContent(
                    100,
                    blockCountList[i],
                    false
                )
                unencryptedBasicSameContentValues.add(Entry(blockCountList[i].toFloat(), result.totalTime.toFloat()))

                println("benchmarking " + i)
                val unencryptedBasicRandomResult = engineBenchmark.unencryptedBasicRandom(
                    100,
                    blockCountList[i],
                    false
                )
                unencryptedBasicRandomValues.add(Entry(blockCountList[i].toFloat(), unencryptedBasicRandomResult.totalTime.toFloat()))


                println("benchmarking " + i)
                val unencryptedBasicRandomSignedResult = engineBenchmark.unencryptedRandomSigned(
                    100,
                    blockCountList[i],
                    false
                )
                unencryptedBasicRandomSignedValues.add(Entry(blockCountList[i].toFloat(), unencryptedBasicRandomSignedResult.totalTime.toFloat()))

                println("benchmarking " + i)
                val unencryptedBasicRandomTrustchainResult = engineBenchmark.unencryptedRandomSignedTrustchainPermanentStorage(
                    requireContext(),
                    100,
                    blockCountList[i],
                    false
                )
                unencryptedBasicRandomTrustchainValues.add(Entry(blockCountList[i].toFloat(), unencryptedBasicRandomTrustchainResult.totalTime.toFloat()))


            }
            val unencrypted = LineDataSet(unencryptedBasicSameContentValues, "unencrypted ")
            val unencryptedRandom = LineDataSet(unencryptedBasicRandomValues, "unencrypted random")
            val unencryptedRandomSigned = LineDataSet(unencryptedBasicRandomSignedValues, "unencrypted random signed")
            val unencryptedRandomSignedTrustchain = LineDataSet(unencryptedBasicRandomTrustchainValues, "unencrypted random signed TrustChain")

            unencrypted.color = R.color.red
            unencryptedRandom.color = R.color.blue
            unencryptedRandomSigned.color = R.color.green
            unencryptedRandomSignedTrustchain.color = R.color.colorPrimary

            allLineData.add(unencrypted)
            allLineData.add(unencryptedRandom)
            allLineData.add(unencryptedRandomSigned)
            allLineData.add(unencryptedRandomSignedTrustchain)

            val lineData : LineData = LineData(allLineData)
            val leftYAxis = chart.axisLeft
            leftYAxis.isEnabled = false

            val rightYAxis = chart.axisRight
            rightYAxis.isEnabled = false

            chart.legend.isWordWrapEnabled = true

            chart.data = lineData
            chart.animateXY(1800, 1800, Easing.EaseInExpo)
        }
    }

    private fun runSpecificRandomBenchmark(builder: AlertDialog, benchmarkResultView: View, engineBenchmark: TransactionEngineBenchmark, type: String) {
        builder.setContentView(benchmarkResultView)
        builder.show()
        val benchmarkTypeTextView = benchmarkResultView.findViewById<TextView>(R.id.benchmarkTypeTextView)
        benchmarkTypeTextView.text = "unencrypted same content"
        val durationCountEditText = benchmarkResultView.findViewById<EditText>(R.id.benchmarkCountDurationEditText)

        val timeRadioButton = benchmarkResultView.findViewById<RadioButton>(R.id.timeRadioButton)
        val blocksRadioButton = benchmarkResultView.findViewById<RadioButton>(R.id.blocksRadioButton)

        blocksRadioButton.isChecked = true
        val graphResolutionEditText = benchmarkResultView.findViewById<EditText>(R.id.graphResolutionEditText)
        val runBenchmarkButton = benchmarkResultView.findViewById<Button>(R.id.runBenchmarkButton)

        val totalTimeTextView = benchmarkResultView.findViewById<TextView>(R.id.totalTimeValueTextView)
        val bandwithTextView = benchmarkResultView.findViewById<TextView>(R.id.bandwithValueTextView)

        val constraintLayout = benchmarkResultView.findViewById<ConstraintLayout>(R.id.resultConstraintLayout)

        constraintLayout.post(Runnable {
            builder.window?.clearFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
            )
        })

        val lineChart = benchmarkResultView.findViewById<LineChart>(R.id.benchmarkLineChart)
        lineChart.setTouchEnabled(true)
        lineChart.setPinchZoom(true)

        lineChart.description.text = "Days"
        lineChart.setNoDataText("No forex yet!")

        runBenchmarkButton.setOnClickListener {
            var resolution = graphResolutionEditText.text.toString()
            if (resolution == "") {
                resolution = "10"
            }

            var blocksOrTime = durationCountEditText.text.toString()
            if (blocksOrTime == "") {
                blocksOrTime = if (timeRadioButton.isChecked) {
                    "1"
                } else {
                    "1000"
                }
            }

            val result : BenchmarkResult

            when (type) {
                "unencryptedBasicRandom" -> result = engineBenchmark.unencryptedBasicRandom(
                    Integer.parseInt(resolution),
                    Integer.parseInt(blocksOrTime),
                    timeRadioButton.isChecked)
                "unencryptedBasicRandomSigned" -> result = engineBenchmark.unencryptedRandomSigned(
                    Integer.parseInt(resolution),
                    Integer.parseInt(blocksOrTime),
                    timeRadioButton.isChecked
                )
                "unencryptedBasicRandomSignedPermanentStorage" -> result = engineBenchmark.unencryptedRandomSignedTrustchainPermanentStorage(
                    requireContext(),
                    Integer.parseInt(resolution),
                    Integer.parseInt(blocksOrTime),
                    timeRadioButton.isChecked
                )
                else -> {
                    if (peers.size > 0) {
                        result = engineBenchmark.encryptedRandomContentSendIPv8(
                            peers[0].peer,
                            requireContext(),
                            Integer.parseInt(resolution),
                            Integer.parseInt(blocksOrTime),
                            timeRadioButton.isChecked)
                    } else {
                        result = engineBenchmark.encryptedRandomContentSendIPv8(
                            null,
                            requireContext(),
                            Integer.parseInt(resolution),
                            Integer.parseInt(blocksOrTime),
                            timeRadioButton.isChecked)
                    }

                }
            }

            val df = DecimalFormat("#.##")
            df.roundingMode = RoundingMode.DOWN
            val roundoff = df.format(result.payloadBandwith)
            totalTimeTextView.text = (result.totalTime / 1000000).toDouble().toString()
            bandwithTextView.text = roundoff

            val dataset = LineDataSet(result.timePerBlock, "time per block")
            dataset.setDrawValues(false)
            dataset.lineWidth = 3f
            lineChart.data = LineData(dataset)

            lineChart.animateX(1800, Easing.EaseInExpo)
        }
    }

    /**
     * Runs when the user hits the "BENCHMARK" button.
     */
    private fun showBenchmarkDialog() {
        val builder = AlertDialog.Builder(requireContext()).create()
        val view = layoutInflater.inflate(R.layout.benchmark_dialog_layout, null)
        val button1 = view.findViewById<Button>(R.id.benchmarkButton1)
        val button2 = view.findViewById<Button>(R.id.benchmarkButton2)
        val button3 = view.findViewById<Button>(R.id.benchmarkButton3)
        val comparisonButton = view.findViewById<Button>(R.id.comparisonButton)
        val button5 = view.findViewById<Button>(R.id.benchMarkButton5)
        val button6 = view.findViewById<Button>(R.id.benchmarkButton6)
        val trustchainBenchmarkButton = view.findViewById<Button>(R.id.trustchainBenchmarkButton)

        val benchmarkResultView = layoutInflater.inflate(R.layout.benchmark_results_noipv8, null)

        builder.setView(view)
        val engineBenchmark = TransactionEngineBenchmark(
            IPv8Android.getInstance().getOverlay<DeToksCommunity>()!!
        )

        button1.setOnClickListener {
            specificBenchmark(builder, benchmarkResultView, engineBenchmark)
        }

        button2.setOnClickListener {
            runSpecificRandomBenchmark(builder, benchmarkResultView, engineBenchmark, "unencryptedBasicRandom")
        }

        button3.setOnClickListener {
            runSpecificRandomBenchmark(builder, benchmarkResultView, engineBenchmark, "unencryptedBasicRandomSigned")
        }

        comparisonButton.setOnClickListener {
            // compare all the non-ipv8 benchmarks
            runComparisonBenchmark(builder, engineBenchmark)
        }

        button5.setOnClickListener {
            runSpecificRandomBenchmark(builder, benchmarkResultView, engineBenchmark, "unencryptedBasicRandomSignedPermanentStorage")
        }

        button6.setOnClickListener {
            runSpecificRandomBenchmark(builder, benchmarkResultView, engineBenchmark, "asdf")
        }

        trustchainBenchmarkButton.setOnClickListener {
            if (peers.size > 0) {
                println("I don't work yet")
            }
        }
        builder.setCanceledOnTouchOutside(true)
        builder.show()
    }
}
