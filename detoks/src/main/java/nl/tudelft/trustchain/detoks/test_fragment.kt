package nl.tudelft.trustchain.detoks

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.PopupWindow
import android.widget.TextView
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.squareup.sqldelight.android.AndroidSqliteDriver
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
import java.util.*

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
        deToksCommunity = IPv8Android.getInstance().getOverlay()!!

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
