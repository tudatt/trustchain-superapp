package nl.tudelft.trustchain.detoks

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.mikephil.charting.animation.Easing
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet
import com.squareup.sqldelight.android.AndroidSqliteDriver
import com.squareup.sqldelight.db.SqlDriver
import com.squareup.sqldelight.sqlite.driver.JdbcSqliteDriver
import kotlinx.android.synthetic.main.test_fragment_layout.*
import nl.tudelft.ipv8.*
import nl.tudelft.ipv8.android.IPv8Android
import nl.tudelft.ipv8.android.keyvault.AndroidCryptoProvider
import nl.tudelft.ipv8.attestation.trustchain.*
import nl.tudelft.ipv8.attestation.trustchain.store.TrustChainSQLiteStore
import nl.tudelft.ipv8.attestation.trustchain.store.TrustChainStore
import nl.tudelft.ipv8.attestation.trustchain.validation.TransactionValidator
import nl.tudelft.ipv8.attestation.trustchain.validation.ValidationResult
import nl.tudelft.ipv8.messaging.Deserializable
import nl.tudelft.ipv8.messaging.Packet
import nl.tudelft.ipv8.messaging.Serializable
import nl.tudelft.ipv8.peerdiscovery.strategy.RandomWalk
import nl.tudelft.ipv8.sqldelight.Database
import nl.tudelft.ipv8.util.hexToBytes
import nl.tudelft.ipv8.util.toHex
import nl.tudelft.trustchain.common.ui.BaseFragment
import java.math.RoundingMode
import java.text.DecimalFormat
import java.util.*
import kotlin.collections.ArrayList

private const val PREF_PRIVATE_KEY = "private_key"

class test_fragment : BaseFragment(R.layout.test_fragment_layout), singleTransactionOnClick, confirmProposalOnClick, benchmark1000, benchmarkBasicToken1000 {

    var peers: ArrayList<PeerViewModel> = arrayListOf()
    var proposals: ArrayList<ProposalViewModel> = arrayListOf()
    lateinit var trustchainInstance: DetoksTrustChainCommunity
    lateinit var deToksCommunity: DeToksCommunity


    var benchmarkStartTime : Long = 0
    var benchmarkEndTime : Long = 0
    var currentBenchmarkIdentifier : String = ""
    var highestReceivedBenchmarkCounter : Int = 0
    var benchmarkCounter : Int = 0


    private fun createTrustChainCommunity(): OverlayConfiguration<TrustChainCommunity> {
        val settings = TrustChainSettings()
        val driver: SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val database = Database(driver)
        val store = TrustChainSQLiteStore(database)
        val randomWalk = RandomWalk.Factory(timeout = 3.0, peers = 20)
        return OverlayConfiguration(
            TrustChainCommunity.Factory(settings, store),
            listOf(randomWalk)
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val privateKey = getPrivateKey(requireContext())


        //Do the trustchain things
        val settings = TrustChainSettings()
        val randomWalk = RandomWalk.Factory()
        // Initialize storage
        val driver = AndroidSqliteDriver(Database.Schema, requireContext(), "detokstrustchain.db")
        val store = TrustChainSQLiteStore(Database(driver))

        // Create the community
        val luuksTrustChainCommunity = OverlayConfiguration(
            DetoksTrustChainCommunity.Factory(settings, store),
            listOf(randomWalk)
        )

        // We now initialize IPv8 with this new community
        val trustChainConfiguration = IPv8Configuration(overlays=listOf(luuksTrustChainCommunity))
        activity?.let { IPv8Android.Factory(it.application).setConfiguration(trustChainConfiguration).setPrivateKey(getPrivateKey(requireContext())).init() }



        // get the deToksCommunity for use in the benchmark
//        deToksCommunity = IPv8Android.getInstance().getOverlay()!!

        // DEFINE BEHAVIOR BOOTSTRAP SERVERS

        // button to show the bootstrap servers
        val button = view.findViewById<Button>(R.id.bootstrapButton)

        button.setOnClickListener {
            // select the popup layout file
            val popupView = LayoutInflater.from(requireContext()).inflate(R.layout.popup_servers, null)

            // open the popup window in the middle of the screen
            val popupWindow = PopupWindow(popupView, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
            popupWindow.showAtLocation(view, Gravity.CENTER, 0, 0)


            val text = popupView.findViewById<TextView>(R.id.text_popup)

            val com = getTrustChainCommunity()

            val walkable = com.getWalkableAddresses()

            var res_str = "BOOTSTRAP SERVERS : \n"

            Community.DEFAULT_ADDRESSES.forEach {address ->
                res_str += address.toString()
                res_str += if (address in walkable) " UP\n"; else " DOWN\n"

            }
            res_str += "\n WALKABLE ADDRESSES :\n" + walkable.joinToString("\n")
            res_str += "\n\nWAN: " + com.myEstimatedWan.toString() + "\n"
            res_str += "`\nLAN: " + com.myEstimatedLan.toString() + "\n"
            text.text = res_str
            // showing the servers on the popup and if they're active or not.

            // close popup window on closebutton click
            val closeButton = popupView.findViewById<Button>(R.id.close_button)
            closeButton.setOnClickListener {
                popupWindow.dismiss()
            }
        }

        val benchmarkDialogButton = view.findViewById<Button>(R.id.benchmark_window_button)
        benchmarkDialogButton.setOnClickListener {
            showBenchmarkDialog(peers)
        }

        val benchmarkTextView = benchmarkStatusTextView
        benchmarkTextView.text = "Currently not running a benchmark."

        val benchmarkCounterTextView = benchmarkCounterTextView
        benchmarkCounterTextView.text = ""


        //val trustchain = IPv8Android.getInstance().getOverlay<DetoksTrustChainCommunity>()!!

        // Call this method to register the validator.
        //registerValidator(trustchain)

        trustchainInstance = IPv8Android.getInstance().getOverlay()!!
        deToksCommunity = IPv8Android.getInstance().getOverlay()!!

        // Call this method to register the validator.
        registerValidator()

        // Register the BlockSigner
        //registerSigner(trustchain)

        val peerRecyclerView = peerListView
        peerRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        val adapter = PeerAdapter(peers, this, this, this)

        peerRecyclerView.adapter = adapter

        val proposalRecyclerView = proposalsListView
        proposalRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        val proposalAdapter = ProposalAdapter(proposals, this)
        proposalRecyclerView.adapter = proposalAdapter

        Thread {

            val trustChainCommunity = IPv8Android.getInstance().getOverlay<DetoksTrustChainCommunity>()
            if (trustChainCommunity != null) {
                while (true) {

                    println("alert: getting peers, found ${Integer.toString(peers.size)}")
                    val peerlist: List<Peer> = trustChainCommunity.getPeers()

                    requireActivity().runOnUiThread {
                        for (peer in peerlist) {
                            if (!peers.contains(PeerViewModel(peer.publicKey.toString(), peer))) {
                                peers.add(PeerViewModel(peer.publicKey.toString(), peer))
                                adapter.notifyItemInserted(peers.size - 1)
                            }

                        }

                        for (index in 0..peers.size - 1) {
                            if (!peerlist.contains(peers[index].peer)) {
                                peers.removeAt(index)
                                adapter.notifyItemRemoved(index)
                            }
                        }
                    }

                    Thread.sleep(50)
                }


            }


        }.start()
        // wow new comment

        // Register the signer that will deal with the incoming benchmark proposals.
        registerBenchmarkSigner()


        // In these listeners, the blocks already went through the validator.
        trustchainInstance.addListener("test_block", object : BlockListener {
            override fun onBlockReceived(block: TrustChainBlock) {
                // If we receive a proposal of the correct type...

                println("${AndroidCryptoProvider.keyFromPrivateBin(privateKey.keyToBin()).pub().keyToBin().toHex()} received a proposal from ${block.publicKey.toHex()}")
                if (block.isProposal && block.publicKey.toHex() !=  AndroidCryptoProvider.keyFromPrivateBin(privateKey.keyToBin()).pub().keyToBin().toHex()) {
                    proposals.add(ProposalViewModel(block.publicKey.toString(), block.type, block))
                    activity?.runOnUiThread{proposalAdapter.notifyItemInserted(proposals.size - 1)}

                }
            }
        })

        trustchainInstance.addListener("benchmark_block", object : BlockListener {
            override fun onBlockReceived(block: TrustChainBlock) {
                if (block.isAgreement && block.publicKey.toHex() !=  AndroidCryptoProvider.keyFromPrivateBin(privateKey.keyToBin()).pub().keyToBin().toHex()) {
                    var counterTV : TextView = benchmarkCounterTextView
                    var indexTV : TextView = benchmarkStatusTextView
                    var benchmarkIndex : Int = Integer.parseInt(block.transaction["message"].toString().drop(9).take(3))
                    println("benchmark: received $benchmarkIndex and index $benchmarkCounter")
                    benchmarkCounter++
                    if (benchmarkIndex > highestReceivedBenchmarkCounter) {
                        highestReceivedBenchmarkCounter = benchmarkIndex
                    }
                    if (benchmarkCounter >= 999 && benchmarkIndex >= 999) {

                        var elapsed : Long = System.nanoTime() - benchmarkStartTime
                        activity?.runOnUiThread { counterTV.text = elapsed.toString() }
                    } else {
                        activity?.runOnUiThread{
                            counterTV.text = highestReceivedBenchmarkCounter.toString()
                            indexTV.text = benchmarkIndex.toString()
                        }

                    }

                }

            }
        })
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
//            graphResolutionEditText.setFocusable(true)
//            graphResolutionEditText.setFocusableInTouchMode(true)
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

        val receiverList : ArrayList<ByteArray> = ArrayList()
        val messageList : ArrayList<ByteArray> = ArrayList()

        val random = Random()

        // generate messages and recipients

            for (i in 0 .. 25000) {
                var receiverArray : ByteArray = ByteArray(64)
                random.nextBytes(receiverArray)
                receiverList.add(receiverArray)

                var messageArray = ByteArray(200)
                random.nextBytes(messageArray)
                messageList.add(messageArray)
            }


        // get unencryptedBasicSameContent
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
                    receiverList,
                    messageList,
                    100,
                    blockCountList[i],
                    false
                )
                unencryptedBasicRandomValues.add(Entry(blockCountList[i].toFloat(), unencryptedBasicRandomResult.totalTime.toFloat()))


                println("benchmarking " + i)
                val unencryptedBasicRandomSignedResult = engineBenchmark.unencryptedRandomSigned(
                    getPrivateKey(requireContext()),
                    receiverList,
                    messageList,
                    100,
                    blockCountList[i],
                    false
                )
                unencryptedBasicRandomSignedValues.add(Entry(blockCountList[i].toFloat(), unencryptedBasicRandomSignedResult.totalTime.toFloat()))

                println("benchmarking " + i)
                val unencryptedBasicRandomTrustchainResult = engineBenchmark.unencryptedRandomSignedTrustchainPermanentStorage(
                    requireContext(),
                    receiverList,
                    messageList,
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
//            graphResolutionEditText.setFocusable(true)
//            graphResolutionEditText.setFocusableInTouchMode(true)
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

            val random : Random = Random()


            val receiverList : ArrayList<ByteArray> = ArrayList()
            val messageList : ArrayList<ByteArray> = ArrayList()
            // generate messages and recipients
            if (blocksRadioButton.isChecked) {

                for (i in 0 .. Integer.parseInt(durationCountEditText.text.toString())) {
                    var receiverArray : ByteArray = ByteArray(64)
                    random.nextBytes(receiverArray)
                    receiverList.add(receiverArray)

                    var messageArray = ByteArray(200)
                    random.nextBytes(messageArray)
                    messageList.add(messageArray)
                }
            } else {
                for (i in 0 .. 1000) {
                    var receiverArray : ByteArray = ByteArray(64)
                    random.nextBytes(receiverArray)
                    receiverList.add(receiverArray)

                    var messageArray = ByteArray(200)
                    random.nextBytes(messageArray)
                    messageList.add(messageArray)
                }
            }

            val result : BenchmarkResult


            when (type) {
                "unencryptedBasicRandom" -> result = engineBenchmark.unencryptedBasicRandom(
                    receiverList,
                    messageList,
                    Integer.parseInt(resolution),
                    Integer.parseInt(blocksOrTime),
                    timeRadioButton.isChecked)
                "unencryptedBasicRandomSigned" -> result = engineBenchmark.unencryptedRandomSigned(
                    getPrivateKey(requireContext()),
                    receiverList,
                    messageList,
                    Integer.parseInt(resolution),
                    Integer.parseInt(blocksOrTime),
                    timeRadioButton.isChecked
                )
                else -> {
                    result = engineBenchmark.unencryptedRandomSignedTrustchainPermanentStorage(
                        requireContext(),
                        receiverList,
                        messageList,
                        Integer.parseInt(resolution),
                        Integer.parseInt(blocksOrTime),
                        timeRadioButton.isChecked
                    )
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

    private fun showBenchmarkDialog(peers: ArrayList<PeerViewModel>) {
        val builder = AlertDialog.Builder(requireContext()).create()
        val view = layoutInflater.inflate(R.layout.benchmark_dialog_layout, null)
        val button1 = view.findViewById<Button>(R.id.benchmarkButton1)
        val button2 = view.findViewById<Button>(R.id.benchmarkButton2)
        val button3 = view.findViewById<Button>(R.id.benchmarkButton3)
        val comparisonButton = view.findViewById<Button>(R.id.comparisonButton)
        val button5 = view.findViewById<Button>(R.id.benchMarkButton5)
        val button6 = view.findViewById<Button>(R.id.benchmarkButton6)
        val trustchainBenchmarkButton = view.findViewById<Button>(R.id.trustchainBenchmarkButton)
//        val button7 = view.findViewById<Button>(R.id.benchmarkButton7)

//        val resultTextView = view.findViewById<TextView>(R.id.resultTimeTextView)





        val benchmarkResultView = layoutInflater.inflate(R.layout.benchmark_results_noipv8, null)

        builder.setView(view)
        val engine = TransactionEngine("c86a7db45eb3563ae047639817baec4db2bc7c25")
        val engineBenchmark = TransactionEngineBenchmark(engine, Peer(getPrivateKey(requireContext())) )

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
            if (peers.size > 0) {
//                val result = engineBenchmark.unencryptedRandomContentSendIPv8(getPrivateKey(requireContext()), requireContext(), peers.get(0).peer, messageList)
//                resultTextView.text = result.toString()
                println(peers[0].peerPK)
            }
        }

        trustchainBenchmarkButton.setOnClickListener {
            if (peers.size > 0) {
//                engineBenchmark.trustchainIpv8Benchmark(trustchainInstance, peers[0].peer, getPrivateKey(requireContext()))
                println("I don't work yet")
            }
        }
        builder.setCanceledOnTouchOutside(true)
        builder.show()
    }

    // We also register a TransactionValidator. This one is simple and checks whether "test_block"s are valid.
    private fun registerValidator() {
        trustchainInstance.registerTransactionValidator("test_block", object : TransactionValidator {
            override fun validate(
                block: TrustChainBlock,
                database: TrustChainStore
            ): ValidationResult {
                // Incoming proposal generated by pressing the create transaction button
                if (block.transaction["message"] == "test message!" && block.isProposal) {
                    println("received a valid proposal from ${block.publicKey.toHex()}")
                    return ValidationResult.Valid
                } else if (block.transaction["message"].toString().take(12) == "benchmark999" && block.isAgreement && block.transaction["message"].toString().takeLast(36) == currentBenchmarkIdentifier) {
                    // In this case, we have received the last agreement of the benchmark and we can stop the timer.

                    return ValidationResult.Valid
                } else if (block.transaction["message"].toString().take(9) == "benchmark" && block.isAgreement) {
                    return ValidationResult.Valid
                } else if (block.isAgreement) {
                    return ValidationResult.Valid
                }
                else {
                    return ValidationResult.Invalid(listOf("This message was not expected and thus will be discarded."))
                }
            }
        })
    }

    // This function registers a BlockSigner. We will notify it of incoming valid blocks, and this method will make sure an agreement is generated for it.
    // Again, this will only register the signer for blocks of type test_block.
    // Note that this signer will be called at all incoming proposals, so it will automatically reply. This will of course be necessary later, but for now it might be nice
    // to reply to incoming proposals manually.
    private fun registerSigner() {
        trustchainInstance.registerBlockSigner("test_block", object : BlockSigner {
            override fun onSignatureRequest(block: TrustChainBlock) {
                trustchainInstance.createAgreementBlock(block, mapOf<Any?, Any?>())
            }
        })
    }

    private fun registerBenchmarkSigner() {
        trustchainInstance.registerBlockSigner("benchmark_block", object : BlockSigner {
            override fun onSignatureRequest(block: TrustChainBlock) {
                trustchainInstance.createAgreementBlock(block, mapOf("message" to block.transaction["message"]))
            }
        })
    }




    // Create a proposal block, store it, and send it to all peers. It sends blocks of type "test_block"
    private fun createProposal(recipient : Peer) {
        // get reference to the trustchain community

        val transaction = mapOf("message" to "test message!")
        println("creating proposal, sending to ${recipient.publicKey.keyToBin()}")
        trustchainInstance.createProposalBlock("test_block", transaction, recipient.publicKey.keyToBin())
    }

    private fun createProposal(recipient: Peer, message: String) {
        val transaction = mapOf("message" to message)
        trustchainInstance.createProposalBlock("test_block", transaction, recipient.publicKey.keyToBin())
    }

    private fun getPrivateKey(context: Context): nl.tudelft.ipv8.keyvault.PrivateKey {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val privateKey = prefs.getString(PREF_PRIVATE_KEY, null)
        val pkTextView = publicKeyTextView
        if (privateKey == null) {
            val newKey = AndroidCryptoProvider.generateKey()
            prefs.edit().putString(PREF_PRIVATE_KEY, newKey.keyToBin().toHex()).apply()
            return newKey
        } else {
            println("Public key: ${AndroidCryptoProvider.keyFromPrivateBin(privateKey.hexToBytes()).pub().keyToBin().toHex()}")
            pkTextView.setText(AndroidCryptoProvider.keyFromPrivateBin(privateKey.hexToBytes()).pub().keyToBin().toHex())
            return AndroidCryptoProvider.keyFromPrivateBin(privateKey.hexToBytes())
        }
    }

    private fun onMessage(packet: Packet) {
        val (peer, payload) = packet.getAuthPayload(MyMessage.Deserializer)
        println("Luuk community: ${peer.mid}: ${payload.message}")
    }

    class MyMessage(val message: String) : Serializable {
        override fun serialize(): ByteArray {
            return message.toByteArray()
        }

        companion object Deserializer : Deserializable<MyMessage> {
            override fun deserialize(buffer: ByteArray, offset: Int): Pair<MyMessage, Int> {
                return Pair(MyMessage(buffer.toString(Charsets.UTF_8)), buffer.size)
            }
        }
    }

    override fun onClick(recipient: Peer) {
        println("alert: onClick called from the adapter!")
        createProposal(recipient)
    }

    override fun confirmProposalClick(block: TrustChainBlock, adapter: ProposalAdapter) {
        trustchainInstance.createAgreementBlock(block, mapOf<Any?, Any?>())
        val iterator = proposals.iterator()
        while (iterator.hasNext()) {
            val proposal = iterator.next()
            if (proposal.block == block) {
                iterator.remove()
                adapter.notifyDataSetChanged()
            }
        }
        println("alert: Agreement should have been sent!")
    }


    // Run the benchmark, do 1000 transactions with a peer.
    // We first generate a unique identifier for this particular instance of the benchmark. We do
    // this to distinguish between the received agreements of different runs of the benchmark.
    override fun runBenchmark(peer: Peer) {
        println("========================== Running benchmark!")
        // we generate the unique identifier as UUID...
        val  benchmarkIdentifier: String = UUID.randomUUID().toString()
        currentBenchmarkIdentifier = benchmarkIdentifier
        benchmarkStartTime = System.nanoTime()
        benchmarkCounter = 0

        Thread(Runnable {
            for (i in 0..999) {
                var index : String = i.toString().padStart(3, '0')
                val transaction = mapOf("message" to "benchmark$index-$benchmarkIdentifier")
                trustchainInstance.createProposalBlock("benchmark_block", transaction, peer.publicKey.keyToBin())
            }
        }).start()

    }

    // In this function we will send 1000 transactions to a peer using the basic token implemented by another group.
    // The goal of this transaction for now is to just send 1000 transactions. We measure on the receiving phone as well.
    override fun runBasicTokenBenchmark(peer: Peer) {
    }

//    fun registerBasicTokenBenchmarkListener() {
//        deToksCommunity.addListener("benchmark", object : BlockListener {
//            override fun onBlockReceived(block: TrustChainBlock) {
//                var counterTV : TextView = benchmarkCounterTextView
//                var indexTV : TextView = benchmarkStatusTextView
//            }
//
//        })
//    }
}

interface singleTransactionOnClick {
    fun onClick(recipient: Peer)
}

interface confirmProposalOnClick {
    fun confirmProposalClick(block: TrustChainBlock, adapter: ProposalAdapter)
}

interface benchmark1000 {
    fun runBenchmark(peer: Peer)
}

interface benchmarkBasicToken1000 {
    fun runBasicTokenBenchmark(peer: Peer)
}
