package com.vinylens.discogs

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import java.io.File

class LibraryActivity : AppCompatActivity() {

    private enum class Sort { ARTIST, TITLE, RECENT }

    private lateinit var searchInput: EditText
    private lateinit var sortChips: ChipGroup
    private lateinit var filterChips: ChipGroup
    private lateinit var countLine: TextView
    private lateinit var recycler: RecyclerView
    private lateinit var emptyState: View

    private var sort = Sort.ARTIST
    private var filter: String? = null      // "genre:Techno" ou "box:Caisse 1"
    private var discs: List<Disc> = emptyList()

    private val adapter = DiscAdapter { disc ->
        startActivity(Intent(this, DiscActivity::class.java).putExtra("id", disc.id))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_library)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        searchInput = findViewById(R.id.searchInput)
        sortChips = findViewById(R.id.sortChips)
        filterChips = findViewById(R.id.filterChips)
        countLine = findViewById(R.id.countLine)
        recycler = findViewById(R.id.recycler)
        emptyState = findViewById(R.id.emptyState)

        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        buildSortChips()
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = render()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
    }

    override fun onResume() {
        super.onResume()
        discs = Library.all(this)
        buildFilterChips()
        render()
    }

    private fun buildSortChips() {
        sortChips.removeAllViews()
        val entries = listOf(
            getString(R.string.sort_artist) to Sort.ARTIST,
            getString(R.string.sort_title) to Sort.TITLE,
            getString(R.string.sort_recent) to Sort.RECENT
        )
        for ((label, value) in entries) {
            val chip = Chip(this)
            chip.text = label
            chip.isCheckable = true
            chip.isChecked = value == sort
            chip.setOnClickListener {
                sort = value
                render()
            }
            sortChips.addView(chip)
        }
    }

    private fun buildFilterChips() {
        filterChips.removeAllViews()

        val all = Chip(this)
        all.text = getString(R.string.filter_all)
        all.isCheckable = true
        all.isChecked = filter == null
        all.setOnClickListener { filter = null; render() }
        filterChips.addView(all)

        for (box in Library.boxes(this)) {
            val key = "box:$box"
            val chip = Chip(this)
            chip.text = box
            chip.isCheckable = true
            chip.isChecked = filter == key
            chip.setOnClickListener { filter = key; render() }
            filterChips.addView(chip)
        }
        for (genre in Library.genres(this).take(14)) {
            val key = "genre:$genre"
            val chip = Chip(this)
            chip.text = genre
            chip.isCheckable = true
            chip.isChecked = filter == key
            chip.setOnClickListener { filter = key; render() }
            filterChips.addView(chip)
        }
    }

    private fun render() {
        val query = searchInput.text.toString()
        var list = discs.filter { it.matches(query) }

        filter?.let { f ->
            val value = f.substringAfter(':')
            list = when {
                f.startsWith("box:") -> list.filter { it.box == value }
                f.startsWith("genre:") -> list.filter { d -> d.genres.any { it == value } }
                else -> list
            }
        }

        list = when (sort) {
            Sort.ARTIST -> list.sortedBy { it.sortKey(byTitle = false) }
            Sort.TITLE -> list.sortedBy { it.sortKey(byTitle = true) }
            Sort.RECENT -> list.sortedByDescending { it.addedAt }
        }

        adapter.submit(list)
        emptyState.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        countLine.text = when {
            discs.isEmpty() -> ""
            list.size == discs.size -> resources.getQuantityString(R.plurals.disc_count, discs.size, discs.size)
            else -> getString(R.string.count_filtered, list.size, discs.size)
        }
    }
}

class DiscAdapter(private val onClick: (Disc) -> Unit) :
    RecyclerView.Adapter<DiscAdapter.VH>() {

    private val items = ArrayList<Disc>()

    fun submit(list: List<Disc>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val cover: ImageView = v.findViewById(R.id.cover)
        val heading: TextView = v.findViewById(R.id.heading)
        val subheading: TextView = v.findViewById(R.id.subheading)
        val boxTag: TextView = v.findViewById(R.id.boxTag)
        val genreTag: TextView = v.findViewById(R.id.genreTag)
        val photoFlag: ImageView = v.findViewById(R.id.photoFlag)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_disc, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val d = items[position]
        holder.heading.text = d.heading()
        holder.subheading.text = d.subheading()

        val source: Any? = when {
            d.coverPath.isNotBlank() && File(d.coverPath).exists() -> File(d.coverPath)
            d.photos.any { File(it).exists() } -> File(d.photos.first { File(it).exists() })
            d.coverUrl.isNotBlank() -> d.coverUrl
            else -> null
        }
        if (source != null) {
            holder.cover.load(source) {
                crossfade(true)
                placeholder(R.drawable.placeholder_cover)
                error(R.drawable.placeholder_cover)
            }
        } else {
            holder.cover.setImageResource(R.drawable.placeholder_cover)
        }

        holder.boxTag.visibility = if (d.box.isBlank()) View.GONE else View.VISIBLE
        holder.boxTag.text = d.box
        holder.genreTag.text = d.genres.take(2).joinToString(" · ")
        holder.photoFlag.visibility = if (d.photos.isEmpty()) View.GONE else View.VISIBLE

        holder.itemView.setOnClickListener { onClick(d) }
    }

    override fun getItemCount(): Int = items.size
}
