package chat.rocket.android.chatroom.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.support.annotation.DrawableRes
import android.support.v4.app.Fragment
import android.support.v7.widget.DefaultItemAnimator
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.text.method.ScrollingMovementMethod
import android.view.*
import android.widget.ImageButton
import chat.rocket.android.R
import chat.rocket.android.chatroom.presentation.ChatRoomPresenter
import chat.rocket.android.chatroom.presentation.ChatRoomView
import chat.rocket.android.chatroom.viewmodel.MessageViewModel
import chat.rocket.android.helper.EndlessRecyclerViewScrollListener
import chat.rocket.android.helper.KeyboardHelper
import chat.rocket.android.helper.MessageParser
import chat.rocket.android.util.extensions.*
import chat.rocket.android.widget.emoji.ComposerEditText
import chat.rocket.android.widget.emoji.Emoji
import chat.rocket.android.widget.emoji.EmojiFragment
import chat.rocket.android.widget.emoji.EmojiParser
import dagger.android.support.AndroidSupportInjection
import kotlinx.android.synthetic.main.fragment_chat_room.*
import kotlinx.android.synthetic.main.message_attachment_options.*
import kotlinx.android.synthetic.main.message_composer.*
import javax.inject.Inject

fun newInstance(chatRoomId: String, chatRoomName: String, chatRoomType: String, isChatRoomReadOnly: Boolean): Fragment {
    return ChatRoomFragment().apply {
        arguments = Bundle(1).apply {
            putString(BUNDLE_CHAT_ROOM_ID, chatRoomId)
            putString(BUNDLE_CHAT_ROOM_NAME, chatRoomName)
            putString(BUNDLE_CHAT_ROOM_TYPE, chatRoomType)
            putBoolean(BUNDLE_IS_CHAT_ROOM_READ_ONLY, isChatRoomReadOnly)
        }
    }
}

private const val BUNDLE_CHAT_ROOM_ID = "chat_room_id"
private const val BUNDLE_CHAT_ROOM_NAME = "chat_room_name"
private const val BUNDLE_CHAT_ROOM_TYPE = "chat_room_type"
private const val BUNDLE_IS_CHAT_ROOM_READ_ONLY = "is_chat_room_read_only"
private const val REQUEST_CODE_FOR_PERFORM_SAF = 42

class ChatRoomFragment : Fragment(), ChatRoomView, EmojiFragment.EmojiKeyboardListener {
    @Inject lateinit var presenter: ChatRoomPresenter
    @Inject lateinit var parser: MessageParser
    private lateinit var adapter: ChatRoomAdapter

    private lateinit var chatRoomId: String
    private lateinit var chatRoomName: String
    private lateinit var chatRoomType: String
    private var isChatRoomReadOnly: Boolean = false

    private lateinit var actionSnackbar: ActionSnackbar
    private var citation: String? = null
    private var editingMessageId: String? = null

    // For reveal and unreveal anim.
    private val hypotenuse by lazy { Math.hypot(root_layout.width.toDouble(), root_layout.height.toDouble()).toFloat() }
    private val max by lazy { Math.max(layout_message_attachment_options.width.toDouble(), layout_message_attachment_options.height.toDouble()).toFloat() }
    private val centerX by lazy { recycler_view.right }
    private val centerY by lazy { recycler_view.bottom }
    val handler = Handler()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidSupportInjection.inject(this)

        val bundle = arguments
        if (bundle != null) {
            chatRoomId = bundle.getString(BUNDLE_CHAT_ROOM_ID)
            chatRoomName = bundle.getString(BUNDLE_CHAT_ROOM_NAME)
            chatRoomType = bundle.getString(BUNDLE_CHAT_ROOM_TYPE)
            isChatRoomReadOnly = bundle.getBoolean(BUNDLE_IS_CHAT_ROOM_READ_ONLY)
        } else {
            requireNotNull(bundle) { "no arguments supplied when the fragment was instantiated" }
        }
        setHasOptionsMenu(true)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? = container?.inflate(R.layout.fragment_chat_room)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        presenter.loadMessages(chatRoomId, chatRoomType)
        setupComposer()
        setupActionSnackbar()
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        attachOrGetEmojiFragment()
        text_message.addTextChangedListener(EmojiFragment.EmojiTextWatcher(text_message))
        text_message.requestFocus()
    }

    override fun onDestroyView() {
        presenter.unsubscribeMessages()
        handler.removeCallbacksAndMessages(null)
        super.onDestroyView()
    }

    override fun onStop() {
        super.onStop()
        hideAllKeyboards()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        if (requestCode == REQUEST_CODE_FOR_PERFORM_SAF && resultCode == Activity.RESULT_OK) {
            if (resultData != null) {
                uploadFile(resultData.data)
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.chatroom_actions, menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_pinned_messages -> {
                val intent = Intent(activity, PinnedMessagesActivity::class.java).apply {
                    putExtra(BUNDLE_CHAT_ROOM_ID, chatRoomId)
                    putExtra(BUNDLE_CHAT_ROOM_TYPE, chatRoomType)
                    putExtra(BUNDLE_CHAT_ROOM_NAME, chatRoomName)
                }
                startActivity(intent)
            }
        }
        return true
    }

    override fun showMessages(dataSet: List<MessageViewModel>) {
        activity?.apply {
            if (recycler_view.adapter == null) {
                adapter = ChatRoomAdapter(chatRoomType, chatRoomName, presenter)
                recycler_view.adapter = adapter
                val linearLayoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, true)
                linearLayoutManager.stackFromEnd = true
                recycler_view.layoutManager = linearLayoutManager
                recycler_view.itemAnimator = DefaultItemAnimator()
                if (dataSet.size >= 30) {
                    recycler_view.addOnScrollListener(object : EndlessRecyclerViewScrollListener(linearLayoutManager) {
                        override fun onLoadMore(page: Int, totalItemsCount: Int, recyclerView: RecyclerView?) {
                            presenter.loadMessages(chatRoomId, chatRoomType, page * 30L)
                        }
                    })
                }
            }
            adapter.addDataSet(dataSet)
            if (adapter.itemCount > 0) {
                recycler_view.scrollToPosition(0)
            }
        }
    }

    override fun sendMessage(text: String) {
        if (!text.isBlank()) {
            presenter.sendMessage(chatRoomId, text, editingMessageId)
        }
    }

    override fun uploadFile(uri: Uri) {
        // TODO Just leaving a blank message that comes with the file for now. In the future lets add the possibility to add a message with the file to be uploaded.
        presenter.uploadFile(chatRoomId, uri, "")
    }

    override fun showInvalidFileMessage() = showMessage(getString(R.string.msg_invalid_file))

    override fun showNewMessage(message: MessageViewModel) {
        adapter.addItem(message)
        recycler_view.smoothScrollToPosition(0)
    }

    override fun disableMessageInput() {
        button_send.isEnabled = false
        text_message.isEnabled = false
    }

    override fun enableMessageInput(clear: Boolean) {
        button_send.isEnabled = true
        text_message.isEnabled = true
        if (clear) text_message.erase()
    }

    override fun dispatchUpdateMessage(index: Int, message: MessageViewModel) {
        adapter.updateItem(message)
    }

    override fun dispatchDeleteMessage(msgId: String) {
        adapter.removeItem(msgId)
    }

    override fun showReplyingAction(username: String, replyMarkdown: String, quotedMessage: String) {
        activity?.apply {
            citation = replyMarkdown
            actionSnackbar.title = username
            actionSnackbar.text = quotedMessage
            actionSnackbar.show()
        }
    }

    override fun showLoading() = view_loading.setVisible(true)

    override fun hideLoading() = view_loading.setVisible(false)

    override fun showMessage(message: String) = showToast(message)

    override fun showMessage(resId: Int) = showToast(resId)

    override fun showGenericErrorMessage() = showMessage(getString(R.string.msg_generic_error))

    override fun copyToClipboard(message: String) {
        activity?.apply {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.primaryClip = ClipData.newPlainText("", message)
        }
    }

    override fun showEditingAction(roomId: String, messageId: String, text: String) {
        activity?.apply {
            actionSnackbar.title = getString(R.string.action_title_editing)
            actionSnackbar.text = text
            actionSnackbar.show()
            text_message.textContent = text
            editingMessageId = messageId
        }
    }

    override fun onEmojiAdded(emoji: Emoji) {
        val cursorPosition = text_message.selectionStart
        if (cursorPosition > -1) {
            text_message.text.insert(cursorPosition, EmojiParser.parse(emoji.shortname))
            text_message.setSelection(cursorPosition + emoji.unicode.length)
        }
    }

    override fun onKeyPressed(keyCode: Int) {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> with(text_message) {
                    if (selectionStart > 0) {
                        text.delete(selectionStart - 1, selectionStart)
                    }
                }
            else -> throw IllegalArgumentException("pressed key not expected")
        }
    }

    private fun setReactionButtonIcon(@DrawableRes drawableId: Int) {
        button_add_reaction.setImageResource(drawableId)
        button_add_reaction.setTag(drawableId)
    }

    private fun hideAllKeyboards() {
        activity?.let {
            KeyboardHelper.hideSoftKeyboard(it)
            attachOrGetEmojiFragment()?.hide()
            setReactionButtonIcon(R.drawable.ic_reaction_24dp)
        }
    }

    private fun setupComposer() {
        if (isChatRoomReadOnly) {
            text_room_is_read_only.setVisible(true)
            input_container.setVisible(false)
        } else {
            var playAnimation = true
            text_message.movementMethod = ScrollingMovementMethod()
            text_message.asObservable(0)
                    .subscribe({ t ->
                        if (t.isNotEmpty() && playAnimation) {
                            button_show_attachment_options.fadeInOrOut(1F, 0F, 120)
                            button_send.fadeInOrOut(0F, 1F, 120)
                            playAnimation = false
                        }

                        if (t.isEmpty()) {
                            button_send.fadeInOrOut(1F, 0F, 120)
                            button_show_attachment_options.fadeInOrOut(0F, 1F, 120)
                            playAnimation = true
                        }
                    })

            text_message.listener = object : ComposerEditText.ComposerEditTextListener {
                override fun onKeyboardOpened() {
                    activity?.let {
                        val fragment = EmojiFragment.getOrAttach(it, R.id.emoji_fragment_placeholder, composer)
                        if (fragment.isCollapsed()) {
                            fragment.show()
                        }
                        setReactionButtonIcon(R.drawable.ic_reaction_24dp)
                    }
                }

                override fun onKeyboardClosed() {
                    activity?.let {
                        setReactionButtonIcon(R.drawable.ic_reaction_24dp)
                        val fragment = EmojiFragment.getOrAttach(it, R.id.emoji_fragment_placeholder, composer)
                        if (fragment.isCollapsed()) {
                            it.onBackPressed()
                        } else {
                            hideAllKeyboards()
                        }
                    }
                }
            }

            button_send.setOnClickListener {
                var textMessage = citation ?: ""
                textMessage += text_message.textContent
                sendMessage(textMessage)
                attachOrGetEmojiFragment()?.let {
                    if (it.softKeyboardVisible) {
                        it.hide()
                    }
                }
                clearActionMessage()
            }


            button_show_attachment_options.setOnClickListener {
                if (layout_message_attachment_options.isShown) {
                    hideAttachmentOptions()
                } else {
                    hideAllKeyboards()
                    showAttachmentOptions()
                }
            }

            view_dim.setOnClickListener { hideAttachmentOptions() }

            button_files.setOnClickListener {
                handler.postDelayed({
                    performSAF()
                }, 300)

                handler.postDelayed({
                    hideAttachmentOptions()
                }, 600)
            }

            button_add_reaction.setOnClickListener { view ->
                activity?.let {
                    val editor = text_message
                    val emojiFragment = attachOrGetEmojiFragment()!!
                    val tag = if (view.tag == null) R.drawable.ic_reaction_24dp else view.tag as Int
                    when (tag) {
                        R.drawable.ic_reaction_24dp -> {
                            KeyboardHelper.hideSoftKeyboard(it)
                            if (!emojiFragment.isShown()) {
                                emojiFragment.show()
                            }
                            setReactionButtonIcon(R.drawable.ic_keyboard_black_24dp)
                        }
                        R.drawable.ic_keyboard_black_24dp -> {
                            KeyboardHelper.showSoftKeyboard(editor)
                            setReactionButtonIcon(R.drawable.ic_reaction_24dp)
                        }
                    }
                }
            }
        }
    }

    private fun attachOrGetEmojiFragment(): EmojiFragment? {
        return activity?.let {
            val frag = EmojiFragment.getOrAttach(it, R.id.emoji_fragment_placeholder, composer)
            frag.listener = this
            frag
        }
    }

    private fun setupActionSnackbar() {
        actionSnackbar = ActionSnackbar.make(message_list_container, parser = parser)
        actionSnackbar.cancelView.setOnClickListener({
            clearActionMessage()
        })
    }

    private fun clearActionMessage() {
        citation = null
        editingMessageId = null
        text_message.text.clear()
        actionSnackbar.dismiss()
    }

    private fun showAttachmentOptions() {
        view_dim.setVisible(true)

        // Play anim.
        button_show_attachment_options.rotateBy(45F)
        layout_message_attachment_options.circularRevealOrUnreveal(centerX, centerY, 0F, hypotenuse)
    }

    private fun hideAttachmentOptions() {
        // Play anim.
        button_show_attachment_options.rotateBy(-45F)
        layout_message_attachment_options.circularRevealOrUnreveal(centerX, centerY, max, 0F)

        view_dim.setVisible(false)
    }

    private fun performSAF() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "*/*"
        startActivityForResult(intent, REQUEST_CODE_FOR_PERFORM_SAF)
    }
}