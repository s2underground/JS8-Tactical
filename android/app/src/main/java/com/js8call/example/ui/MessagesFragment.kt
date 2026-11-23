package com.js8call.example.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import com.js8call.example.R
import java.util.Locale

/**
 * Fragment showing the list of conversations (Messages tab).
 */
class MessagesFragment : Fragment() {

    private lateinit var viewModel: MessagesViewModel
    private lateinit var decodeViewModel: DecodeViewModel
    private lateinit var adapter: ConversationListAdapter

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: LinearLayout
    private lateinit var newMessageFab: FloatingActionButton

    private var conversationCallsigns: List<String> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_messages, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize ViewModel (shared with ConversationFragment)
        viewModel = ViewModelProvider(requireActivity())[MessagesViewModel::class.java]
        decodeViewModel = ViewModelProvider(requireActivity())[DecodeViewModel::class.java]

        // Find views
        recyclerView = view.findViewById(R.id.conversations_recycler_view)
        emptyState = view.findViewById(R.id.empty_state)
        newMessageFab = view.findViewById(R.id.new_message_fab)

        setUpStoreForward(view)

        view.findViewById<View>(R.id.everything_card).setOnClickListener {
            findNavController().navigate(R.id.action_messages_to_everything)
        }
        val everythingPreview = view.findViewById<android.widget.TextView>(R.id.everything_preview)
        decodeViewModel.decodes.observe(viewLifecycleOwner) { decodes ->
            everythingPreview.text = decodes.lastOrNull()?.text
                ?: getString(R.string.everything_subtitle)
        }

        // Set up RecyclerView
        adapter = ConversationListAdapter().apply {
            onItemClick = { conversation ->
                navigateToConversation(conversation.callsign)
            }
            onItemLongClick = { conversation ->
                showConversationOptions(conversation.callsign)
                true
            }
        }
        recyclerView.adapter = adapter

        newMessageFab.setOnClickListener {
            showNewMessageDialog()
        }

        // Observe conversations
        viewModel.conversations.observe(viewLifecycleOwner) { conversations ->
            adapter.submitList(conversations)
            conversationCallsigns = conversations.map { it.callsign }

            // Show/hide empty state
            if (conversations.isEmpty()) {
                emptyState.visibility = View.VISIBLE
                recyclerView.visibility = View.GONE
            } else {
                emptyState.visibility = View.GONE
                recyclerView.visibility = View.VISIBLE
            }
        }
    }

    // ------------------------------------------------------------------ store and forward

    private var sfTab = 0
    private lateinit var waitingBox: LinearLayout
    private lateinit var heldBox: LinearLayout
    private lateinit var heldScroll: android.widget.ScrollView
    private lateinit var forMeViews: List<View>
    private lateinit var tabs: com.google.android.material.button.MaterialButtonToggleGroup

    /** Tabs over the Inbox: For me (conversations), Holding (for other stations), Posted (our reports). */
    private fun setUpStoreForward(view: View) {
        val ctx = requireContext()
        val column = (view as ViewGroup).getChildAt(0) as LinearLayout
        val forMe = (0 until column.childCount).map { column.getChildAt(it) }
        val dp = resources.displayMetrics.density
        tabs = com.google.android.material.button.MaterialButtonToggleGroup(ctx).apply {
            isSingleSelection = true; isSelectionRequired = true
            setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (4 * dp).toInt())
        }
        val ids = listOf(R.string.sf_tab_for_me, R.string.sf_tab_holding, R.string.sf_tab_posted).map { label ->
            com.google.android.material.button.MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                id = View.generateViewId(); text = getString(label); isAllCaps = false
                // Selected tab: solid amber with dark text, so it reads at a glance.
                val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
                fun csl(on: Int, off: Int) = android.content.res.ColorStateList(states, intArrayOf(
                    androidx.core.content.ContextCompat.getColor(ctx, on), androidx.core.content.ContextCompat.getColor(ctx, off)))
                backgroundTintList = csl(R.color.tac_accent, R.color.tac_card)
                setTextColor(csl(R.color.tac_ground, R.color.tac_text_dim))
                strokeColor = csl(R.color.tac_accent, R.color.tac_border)
                tabs.addView(this, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }.id
        }
        tabs.check(ids[sfTab])
        tabs.addOnButtonCheckedListener { _, id, checked -> if (checked) { sfTab = ids.indexOf(id); showSfTab() } }
        column.addView(tabs, 0)
        waitingBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        column.addView(waitingBox, 1)
        heldBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        heldScroll = android.widget.ScrollView(ctx).apply { addView(heldBox); visibility = View.GONE }
        column.addView(heldScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        forMeViews = listOf(waitingBox) + forMe
        com.js8call.example.storeforward.HeldStore.of(ctx).changes.observe(viewLifecycleOwner) { showSfTab() }
    }

    private fun showSfTab() {
        if (view == null) return
        val store = com.js8call.example.storeforward.HeldStore.of(requireContext())
        val all = store.all()
        val counts = listOf(store.waiting().size, all.count { it.kind == com.js8call.example.storeforward.Held.Kind.RELAY },
            all.count { it.kind == com.js8call.example.storeforward.Held.Kind.POSTED })
        val labels = listOf(R.string.sf_tab_for_me, R.string.sf_tab_holding, R.string.sf_tab_posted)
        for (i in 0 until tabs.childCount) {
            (tabs.getChildAt(i) as com.google.android.material.button.MaterialButton).text =
                getString(labels[i]) + if (counts[i] > 0) " (${counts[i]})" else ""
        }
        forMeViews.forEach { it.visibility = if (sfTab == 0) View.VISIBLE else View.GONE }
        if (sfTab == 0) {
            com.js8call.example.ui.StoreForwardUi.renderWaiting(this, waitingBox)
        }
        heldScroll.visibility = if (sfTab == 0) View.GONE else View.VISIBLE
        newMessageFab.visibility = if (sfTab == 0) View.VISIBLE else View.GONE
        if (sfTab != 0) com.js8call.example.ui.StoreForwardUi.renderHeld(this, heldBox,
            if (sfTab == 1) com.js8call.example.storeforward.Held.Kind.RELAY else com.js8call.example.storeforward.Held.Kind.POSTED)
    }

    private fun showNewMessageDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_new_message, null)
        val inputLayout = dialogView.findViewById<TextInputLayout>(R.id.callsign_input_layout)
        val input = dialogView.findViewById<MaterialAutoCompleteTextView>(R.id.callsign_input)

        // Conversation history plus stations heard this session
        val suggestions = (conversationCallsigns + decodeViewModel.heardCallsigns()).distinct()
        input.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, suggestions)
        )
        input.threshold = 1
        input.doAfterTextChanged { inputLayout.error = null }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.messages_new)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val callsign = input.text?.toString()?.trim()?.uppercase(Locale.ROOT).orEmpty()
            val error = threadAddressError(callsign, heard = callsign in suggestions)
            if (error != null) {
                inputLayout.error = getString(error)
                return@setOnClickListener
            }
            dialog.dismiss()
            navigateToConversation(callsign)
        }
    }

    private fun navigateToConversation(callsign: String) {
        val bundle = Bundle().apply {
            putString("callsign", callsign)
        }
        findNavController().navigate(R.id.action_messages_to_conversation, bundle)
    }

    private fun showConversationOptions(callsign: String) {
        val options = arrayOf(
            getString(R.string.messages_delete_conversation)
        )

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(callsign)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> confirmDeleteConversation(callsign)
                }
            }
            .show()
    }

    private fun confirmDeleteConversation(callsign: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.messages_delete_conversation)
            .setMessage(getString(R.string.messages_delete_confirm, callsign))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                viewModel.deleteConversation(callsign)
                Snackbar.make(requireView(), R.string.messages_deleted, Snackbar.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
