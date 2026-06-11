package com.stellar.app.ui

import android.app.Dialog
import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.stellar.app.R
import com.stellar.app.data.SkyObject
import com.stellar.app.databinding.DialogSearchBinding

/**
 * Find a star, planet or constellation by name; selecting a result marks
 * it in the AR view (with a guidance arrow when it is off screen).
 */
class SearchDialogFragment : DialogFragment() {

    private val viewModel: SkyViewModel by activityViewModels()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = DialogSearchBinding.inflate(layoutInflater)
        val results = ArrayList<SkyObject>()
        val adapter = ArrayAdapter<String>(
            requireContext(), android.R.layout.simple_list_item_1
        )
        binding.searchResults.adapter = adapter

        fun refresh(query: String) {
            results.clear()
            results.addAll(viewModel.search(query))
            adapter.clear()
            adapter.addAll(results.map { describe(it) })
            adapter.notifyDataSetChanged()
        }

        binding.searchInput.addTextChangedListener { text ->
            refresh(text?.toString().orEmpty())
        }
        binding.searchResults.setOnItemClickListener { _, _, position, _ ->
            viewModel.select(results[position])
            dismiss()
        }
        refresh("")

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.search_title)
            .setView(binding.root)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    private fun describe(obj: SkyObject): String {
        val type = when (obj.type) {
            SkyObject.Type.STAR -> getString(R.string.type_star)
            SkyObject.Type.PLANET -> getString(R.string.type_planet)
            SkyObject.Type.MOON -> getString(R.string.type_moon)
            SkyObject.Type.SUN -> getString(R.string.type_sun)
            SkyObject.Type.CONSTELLATION -> getString(R.string.type_constellation)
        }
        return if (obj.type == SkyObject.Type.CONSTELLATION) {
            "${obj.name} — $type"
        } else {
            "${obj.displayLabel()} — $type, mag %.1f".format(obj.mag)
        }
    }

    companion object {
        const val TAG = "SearchDialog"
    }
}
