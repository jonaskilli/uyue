package io.legado.app.ui.book.read.config

import android.os.Bundle
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.CharacterProfile
import io.legado.app.databinding.ActivityCharacterProfileManageBinding
import io.legado.app.databinding.ItemCharacterProfileBinding
import io.legado.app.databinding.ItemCharacterProfileGroupBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.model.AiImageGenerator
import io.legado.app.model.CharacterProfileManager
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AI 角色档案总揽。
 *
 * 原来没有独立的档案总览入口：只能在阅读界面打开「角色管理」长按某个角色，
 * 或者在别处找到该角色后点进去才能看到 [CharacterProfileDialog]。这里把
 * `characterProfiles` 表里的全部档案按**书**分组列出来：
 * - 书头可点击折叠/展开，换书时不必长距离滚动
 * - 书头右侧 🗑 删掉这本书的全部档案
 * - 点角色 → 进入该角色的档案界面
 * - 长按角色 → 查看档案 / 与角色对话 / 生成立绘 / 删除该角色档案
 *
 * 入口放在书架右上角：书删掉后也能直接进来，不用先找一本书打开。
 *
 * 注意与阅读界面那个「角色管理」的区别：后者是文件驱动（`shuming.<书名>.json`，
 * 存的是姓名/别名/性别年龄），且点击不会进档案；这里读的是 AI 建档后的 DB 档案。
 */
class CharacterProfileManageActivity :
    BaseActivity<ActivityCharacterProfileManageBinding>() {

    override val binding by viewBinding(ActivityCharacterProfileManageBinding::inflate)

    /** 进来时定位的书（从书籍详情进入才有），排在最前 */
    private val currentBookName get() = intent?.getStringExtra(EXTRA_BOOK_NAME).orEmpty()

    /** 折叠起来的书，key 是书名 */
    private val collapsedBooks = mutableSetOf<String>()

    /** 首次加载时把所有书都折叠起来 */
    private var firstLoad = true

    private val adapter by lazy {
        CharacterProfileAdapter(
            onItemClick = { showProfile(it.bookName, it.profile.name) },
            onItemLongClick = { showItemMenu(it); true },
            onDeleteBook = { confirmDeleteBook(it) },
            onToggleExpand = { toggleBook(it) }
        )
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.recyclerView.applyNavigationBarPadding()
        binding.recyclerView.adapter = adapter
        binding.tvEmpty.setOnClickListener { loadData() }
        savedInstanceState?.getStringArrayList(STATE_COLLAPSED)?.let { collapsedBooks.addAll(it) }
        adapter.setCollapsedBooks(collapsedBooks)
    }

    override fun onResume() {
        super.onResume()
        // 从档案弹窗里删除/编辑完返回时同步一次
        loadData()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(STATE_COLLAPSED, ArrayList(collapsedBooks))
    }

    private fun toggleBook(bookName: String) {
        if (!collapsedBooks.add(bookName)) collapsedBooks.remove(bookName)
        adapter.setCollapsedBooks(collapsedBooks)
    }

    private fun loadData() {
        lifecycleScope.launch {
            val profiles = withContext(IO) {
                runCatching { appDb.characterProfileDao.getAllList() }
                    .onFailure {
                        AppLog.put("角色档案总揽获取数据失败\n${it.localizedMessage}", it)
                    }
                    .getOrDefault(emptyList())
            }
            val items = buildItems(profiles)
            // 默认全部折叠: 角色多的书全展开时列表能拉很长, 换书得上下翻半天
            if (firstLoad) {
                firstLoad = false
                // collapsedBooks 非空说明是屏幕旋转恢复的, 别覆盖用户的选择
                if (collapsedBooks.isEmpty() && items.isNotEmpty()) {
                    collapsedBooks.addAll(
                        items.filterIsInstance<CharacterProfileAdapter.Item.BookHeader>()
                            .map { it.bookName }
                    )
                    adapter.setCollapsedBooks(collapsedBooks)
                }
            }
            adapter.setItems(items)
            // 空列表时给一句说明(布局里已有默认文案), 点击可重新加载
            binding.tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    /**
     * 按书分组，同书内按出场次数/信息充分度倒序。进来时的那本书排最前。
     */
    private fun buildItems(profiles: List<CharacterProfile>): List<CharacterProfileAdapter.Item> {
        if (profiles.isEmpty()) return emptyList()
        val groups = profiles.groupBy { it.bookUrl }
        val bookNames = groups.keys.map { it.removePrefix(BOOK_KEY_PREFIX) }
        val sorted = bookNames.sortedWith(
            compareByDescending<String> { it == currentBookName }.thenBy { it }
        )
        val items = ArrayList<CharacterProfileAdapter.Item>(profiles.size + sorted.size)
        sorted.forEach { bookName ->
            val list = groups[CharacterProfileManager.bookKey(bookName)].orEmpty()
            items.add(CharacterProfileAdapter.Item.BookHeader(bookName, list.size))
            list.sortedWith(
                compareByDescending<CharacterProfile> { it.mentionCount }
                    .thenByDescending { it.confidence }
                    .thenBy { it.name }
            ).forEach {
                items.add(CharacterProfileAdapter.Item.Profile(bookName, it))
            }
        }
        return items
    }

    private fun showProfile(bookName: String, characterName: String) {
        showDialogFragment(CharacterProfileDialog.newInstance(bookName, characterName))
    }

    private fun showItemMenu(item: CharacterProfileAdapter.Item.Profile) {
        val bookName = item.bookName
        val name = item.profile.name
        val hasAvatar = AiImageGenerator.getCachedAvatar(bookName, name) != null
        val options = arrayOf(
            "角色档案",
            "与角色对话",
            if (hasAvatar) "重新生成立绘" else "生成立绘",
            "删除该角色档案"
        )
        alert("角色操作 · $name") {
            items(options.toList()) { _, which ->
                when (which) {
                    0 -> showProfile(bookName, name)
                    1 -> RoleChatActivity.start(this@CharacterProfileManageActivity, bookName, name)
                    2 -> generateAvatar(bookName, name)
                    3 -> confirmDeleteProfile(bookName, name)
                }
            }
        }
    }

    private fun generateAvatar(bookName: String, name: String) {
        val profile = CharacterProfileManager.getProfile(bookName, name)
        toastOnUi("正在生成立绘…")
        AiImageGenerator.generateCharacterAvatar(
            characterName = name,
            genderAge = listOf(profile?.gender.orEmpty(), profile?.age.orEmpty())
                .filter { it.isNotBlank() }
                .joinToString(""),
            bookName = bookName,
            identity = profile?.identity.orEmpty(),
            personality = profile?.personality.orEmpty(),
            background = profile?.background.orEmpty(),
            regenerate = true,
            onSuccess = {
                toastOnUi("立绘已生成")
                loadData()
            },
            onFailure = { toastOnUi("立绘生成失败：${it.message}") }
        )
    }

    private fun confirmDeleteProfile(bookName: String, name: String) {
        alert(getString(R.string.character_profile_delete_title)) {
            setMessage("将删除「$name」的档案，朗读时不再自动重建。确定？")
            okButton {
                CharacterProfileManager.delete(bookName, name)
                toastOnUi("已删除「$name」的档案")
                loadData()
            }
            cancelButton()
        }
    }

    private fun confirmDeleteBook(bookName: String) {
        val count = adapter.countOfBook(bookName)
        alert(getString(R.string.character_profile_delete_title)) {
            setMessage("将删除《$bookName》的全部 $count 个角色档案，朗读时不再自动重建。确定？")
            okButton {
                CharacterProfileManager.clearBook(bookName)
                toastOnUi("已删除《$bookName》的全部档案")
                loadData()
            }
            cancelButton()
        }
    }

    companion object {
        const val EXTRA_BOOK_NAME = "bookName"
        private const val BOOK_KEY_PREFIX = "script:"
        private const val STATE_COLLAPSED = "collapsedBooks"
    }
}

/**
 * 分组列表：书头 + 角色条目两种布局，书头可折叠。
 *
 * 这里直接用 RecyclerView.Adapter 而不是项目的 RecyclerAdapter ——
 * 后者只支持单一 ViewBinding 类型，做不了两种 item 布局。
 *
 * 折叠在 Adapter 内部做：保存完整列表 allItems，按 [collapsed] 过滤出真正显示的
 * shownItems。这样"展开/收起"只是重算显示列表，不用重新查库。
 */
class CharacterProfileAdapter(
    private val onItemClick: (Item.Profile) -> Unit,
    private val onItemLongClick: (Item.Profile) -> Boolean,
    private val onDeleteBook: (String) -> Unit,
    private val onToggleExpand: (String) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed class Item {
        data class BookHeader(val bookName: String, val count: Int) : Item()
        data class Profile(val bookName: String, val profile: CharacterProfile) : Item()
    }

    private val allItems = ArrayList<Item>()
    private val shownItems = ArrayList<Item>()
    private val collapsed = mutableSetOf<String>()

    fun setItems(newItems: List<Item>) {
        allItems.clear()
        allItems.addAll(newItems)
        rebuild()
    }

    fun setCollapsedBooks(books: Set<String>) {
        collapsed.clear()
        collapsed.addAll(books)
        rebuild()
    }

    /** 该书的角色数（含折叠状态，用于删除前提示） */
    fun countOfBook(bookName: String): Int =
        allItems.count { it is Item.Profile && it.bookName == bookName }

    private fun rebuild() {
        shownItems.clear()
        var i = 0
        while (i < allItems.size) {
            val item = allItems[i]
            if (item is Item.BookHeader) {
                shownItems.add(item)
                var j = i + 1
                if (item.bookName !in collapsed) {
                    // 展开时把该书的角色整段带上，遇到下一个书头就停
                    while (j < allItems.size && allItems[j] is Item.Profile) {
                        shownItems.add(allItems[j])
                        j++
                    }
                }
                i = j
            } else {
                i++
            }
        }
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int) =
        if (shownItems[position] is Item.BookHeader) TYPE_HEADER else TYPE_PROFILE

    override fun getItemCount(): Int = shownItems.size

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = android.view.LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderHolder(ItemCharacterProfileGroupBinding.inflate(inflater, parent, false))
        } else {
            ProfileHolder(ItemCharacterProfileBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = shownItems[position]) {
            is Item.BookHeader -> (holder as HeaderHolder).bind(item)
            is Item.Profile -> (holder as ProfileHolder).bind(item)
        }
    }

    private inner class HeaderHolder(
        private val bind: ItemCharacterProfileGroupBinding
    ) : RecyclerView.ViewHolder(bind.root) {

        fun bind(item: Item.BookHeader) {
            bind.tvBookName.text = item.bookName
            bind.tvCount.text = "${item.count} 个角色"
            // 展开时箭头朝下，折叠时朝右
            bind.ivExpand.rotation = if (item.bookName in collapsed) -90f else 0f
            bind.root.setOnClickListener { onToggleExpand(item.bookName) }
            bind.ivDelete.setOnClickListener { onDeleteBook(item.bookName) }
        }
    }

    private inner class ProfileHolder(
        private val bind: ItemCharacterProfileBinding
    ) : RecyclerView.ViewHolder(bind.root) {

        fun bind(item: Item.Profile) {
            val p = item.profile
            bind.tvName.text = p.name
            val genderAge = listOf(p.gender, p.age).filter { it.isNotBlank() }.joinToString(" ")
            bind.tvIdentity.text = listOf(
                genderAge,
                p.identity.ifBlank { "身份待补全" },
                if (p.isComplete) "档案完整" else "待补全(${(p.confidence * 100).toInt()}%)"
            ).joinToString(" · ")
            bind.root.setOnClickListener { onItemClick(item) }
            bind.root.setOnLongClickListener { onItemLongClick(item) }
            loadAvatar(bind.ivAvatar, item.bookName, p.name)
        }

        /**
         * 立绘是本地缓存文件，按顶端裁正方形（与档案弹窗一致，保证看得到头部）。
         * 列表项尺寸小，同步解码开销可接受；用 tag 记住已加载的角色，避免复用错位。
         */
        private fun loadAvatar(iv: android.widget.ImageView, bookName: String, name: String) {
            val key = "$bookName/$name"
            if (iv.tag == key) return
            val file = AiImageGenerator.getCachedAvatar(bookName, name)
            if (file == null) {
                iv.setImageResource(R.drawable.ic_portrait_person)
                iv.tag = key
                return
            }
            val bmp = AiImageGenerator.decodeAvatarTopSquare(file, AVATAR_SIZE_PX)
            if (bmp != null) {
                iv.setImageBitmap(bmp)
            } else {
                iv.setImageResource(R.drawable.ic_portrait_person)
            }
            iv.tag = key
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_PROFILE = 1
        private const val AVATAR_SIZE_PX = 160
    }
}
