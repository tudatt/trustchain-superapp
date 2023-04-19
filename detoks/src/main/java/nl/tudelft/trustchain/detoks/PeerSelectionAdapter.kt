package nl.tudelft.trustchain.detoks

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class PeerSelectionAdapter (private val mList: List<PeerViewModel>, val choosePeer: choosePeerForBenchmark) : RecyclerView.Adapter<PeerSelectionAdapter.ViewHolder>(){
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.discovered_peer_item, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int {
        return mList.size
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        var pkView : TextView = holder.pkView
        var peerButton : Button = holder.selectPeerButton
        pkView.text = mList[position].peerPublicKey.takeLast(5)
        peerButton.setOnClickListener {
            choosePeer.choicePeer(mList[position].peer, choosePeer.getBuilder(), choosePeer.getResultView(), choosePeer.getEngineBenchmark(), choosePeer.getIPv8BenchmarkType())
        }
    }

    class ViewHolder(itemView : View) : RecyclerView.ViewHolder(itemView) {
        var pkView : TextView = itemView.findViewById(R.id.pkTextview)
        var selectPeerButton : Button = itemView.findViewById(R.id.selectPeerButton)
    }

}
