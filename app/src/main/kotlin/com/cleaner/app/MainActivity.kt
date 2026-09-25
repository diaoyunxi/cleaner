package com.cleaner.app

import android.Manifest
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 主界面 Activity
 *
 * 功能流程:
 * 1. 检查/请求 MANAGE_EXTERNAL_STORAGE 权限
 * 2. 显示扫描预览（可扫描的目录列表）
 * 3. 执行三级哈希扫描（大小 -> XXH64 -> SHA-256）
 * 4. 展示重复文件列表，支持排序、自动选择、手动调整
 * 5. 用户确认后批量删除
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        /** 所有文件访问权限请求码 */
        private const val REQUEST_MANAGE_STORAGE = 1001
    }

    // 扫描引擎
    private lateinit var scanner: FileScanner

    // UI 组件 (使用 ViewBinding 手动引用)
    private lateinit var toolbar: MaterialToolbar
    private lateinit var cardStatus: MaterialCardView
    private lateinit var tvStatusText: TextView
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var llProgressDetail: LinearLayout
    private lateinit var tvFilesScanned: TextView
    private lateinit var tvGroupsFound: TextView
    private lateinit var tvWasteSize: TextView
    private lateinit var llActionBar: LinearLayout
    private lateinit var btnAutoSelect: MaterialButton
    private lateinit var btnDelete: MaterialButton
    private lateinit var llEmptyState: LinearLayout
    private lateinit var tvEmptyText: TextView
    private lateinit var btnGrantPermission: MaterialButton
    private lateinit var btnStartScan: MaterialButton
    private lateinit var recyclerView: RecyclerView

    // 扫描状态
    private var scanJob: Job? = null
    private var duplicateGroups: List<DuplicateGroup> = emptyList()
    private var adapter: DuplicateGroupAdapter? = null
    private var isDarkMode = false

    // 排序状态
    private enum class SortMode {
        SIZE_DESC, SIZE_ASC, COUNT_DESC, COUNT_ASC, NAME_ASC, NAME_DESC, WASTE_DESC
    }
    private var currentSort = SortMode.SIZE_DESC

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 初始化视图引用
        toolbar = findViewById(R.id.toolbar)
        cardStatus = findViewById(R.id.card_status)
        tvStatusText = findViewById(R.id.tv_status_text)
        progressBar = findViewById(R.id.progress_bar)
        llProgressDetail = findViewById(R.id.ll_progress_detail)
        tvFilesScanned = findViewById(R.id.tv_files_scanned)
        tvGroupsFound = findViewById(R.id.tv_groups_found)
        tvWasteSize = findViewById(R.id.tv_waste_size)
        llActionBar = findViewById(R.id.ll_action_bar)
        btnAutoSelect = findViewById(R.id.btn_auto_select)
        btnDelete = findViewById(R.id.btn_delete)
        llEmptyState = findViewById(R.id.ll_empty_state)
        tvEmptyText = findViewById(R.id.tv_empty_text)
        btnGrantPermission = findViewById(R.id.btn_grant_permission)
        btnStartScan = findViewById(R.id.btn_start_scan)
        recyclerView = findViewById(R.id.recycler_view)

        // 设置 Toolbar
        setSupportActionBar(toolbar)
        supportActionBar?.title = getString(R.string.app_name)

        // 初始化扫描器
        scanner = FileScanner(this)

        // 初始化 RecyclerView
        recyclerView.layoutManager = LinearLayoutManager(this)

        // 按钮事件
        btnGrantPermission.setOnClickListener { requestManageStoragePermission() }
        btnStartScan.setOnClickListener { showScanPreview() }
        btnAutoSelect.setOnClickListener { onAutoSelect() }
        btnDelete.setOnClickListener { onDelete() }

        // 检查权限状态
        updatePermissionUI()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionUI()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        // 设置深色模式开关状态
        val darkItem = menu?.findItem(R.id.action_dark_mode)
        darkItem?.isChecked = isDarkMode
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_sort_size_desc -> { currentSort = SortMode.SIZE_DESC; applySort(); return true }
            R.id.action_sort_count_desc -> { currentSort = SortMode.COUNT_DESC; applySort(); return true }
            R.id.action_sort_name_asc -> { currentSort = SortMode.NAME_ASC; applySort(); return true }
            R.id.action_sort_waste_desc -> { currentSort = SortMode.WASTE_DESC; applySort(); return true }
            R.id.action_dark_mode -> {
                isDarkMode = !isDarkMode
                item.isChecked = isDarkMode
                applyTheme()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    // ======================== 权限管理 ========================

    /**
     * 检查是否拥有所有文件访问权限
     */
    private fun hasManageStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    /**
     * 请求所有文件访问权限
     * 跳转到系统设置页面
     */
    private fun requestManageStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, REQUEST_MANAGE_STORAGE)
            } catch (e: Exception) {
                Log.w(TAG, "无法打开精确权限页面，回退到通用设置", e)
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                startActivityForResult(intent, REQUEST_MANAGE_STORAGE)
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MANAGE_STORAGE) {
            updatePermissionUI()
        }
    }

    /**
     * 根据权限状态更新 UI
     */
    private fun updatePermissionUI() {
        if (hasManageStoragePermission()) {
            tvEmptyText.text = getString(R.string.hint_idle)
            btnGrantPermission.visibility = View.GONE
            btnStartScan.visibility = View.VISIBLE
        } else {
            tvEmptyText.text = getString(R.string.hint_no_permission)
            btnGrantPermission.visibility = View.VISIBLE
            btnStartScan.visibility = View.GONE
        }
    }

    // ======================== 主题切换 ========================

    /**
     * 应用主题（深色/浅色）
     */
    private fun applyTheme() {
        val theme = if (isDarkMode) R.style.Theme_Cleaner_Dark else R.style.Theme_Cleaner
        setTheme(theme)
        recreate()
    }

    // ======================== 扫描流程 ========================

    /**
     * 显示扫描预览对话框
     * 列出将要扫描的目录，让用户确认
     */
    private fun showScanPreview() {
        if (!hasManageStoragePermission()) {
            requestManageStoragePermission()
            return
        }

        val roots = scanner.getScanRoots()
        if (roots.isEmpty()) {
            Toast.makeText(this, R.string.preview_empty, Toast.LENGTH_SHORT).show()
            return
        }

        // 收集所有子目录
        val allDirs = mutableListOf<String>()
        for (root in roots) {
            allDirs.add(root.absolutePath)
            val subs = scanner.getSubDirectories(root)
            allDirs.addAll(subs.map { it.absolutePath })
        }

        // 构建预览对话框
        val dialogView = layoutInflater.inflate(R.layout.dialog_preview, null)
        val tvSummary = dialogView.findViewById<TextView>(R.id.tv_preview_summary)
        val recyclerDirs = dialogView.findViewById<RecyclerView>(R.id.recycler_dirs)

        tvSummary.text = getString(R.string.preview_summary, allDirs.size)

        // 目录列表
        recyclerDirs.layoutManager = LinearLayoutManager(this)
        val dirAdapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount(): Int = allDirs.size

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val tv = TextView(parent.context).apply {
                    val pad = (8 * resources.displayMetrics.density).toInt()
                    setPadding(pad * 3, pad, pad, pad)
                    setTextAppearance(androidx.appcompat.R.style.TextAppearance_AppCompat_Medium)
                }
                return object : RecyclerView.ViewHolder(tv) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                (holder.itemView as TextView).text = allDirs[position]
            }
        }
        recyclerDirs.adapter = dirAdapter

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.preview_title)
            .setView(dialogView)
            .setPositiveButton(R.string.btn_start_confirm) { _, _ ->
                startScan(roots)
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    /**
     * 开始扫描重复文件
     *
     * @param roots 要扫描的根目录列表
     */
    private fun startScan(roots: List<File>) {
        // 重置状态
        duplicateGroups = emptyList()
        adapter = null
        recyclerView.adapter = null
        llEmptyState.visibility = View.GONE
        recyclerView.visibility = View.GONE
        llActionBar.visibility = View.GONE
        cardStatus.visibility = View.VISIBLE
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        llProgressDetail.visibility = View.VISIBLE
        tvStatusText.text = getString(R.string.hint_scanning)

        // 取消之前的扫描任务
        scanJob?.cancel()

        scanJob = lifecycleScope.launch {
            scanner.scanDuplicates(roots).collect { progress ->
                // 在主线程更新 UI
                progressBar.isIndeterminate = (progress.filesScanned == 0)
                tvFilesScanned.text = getString(R.string.progress_files, progress.filesScanned)
                tvGroupsFound.text = getString(R.string.progress_groups, progress.groupsFound)
                tvWasteSize.text = getString(
                    R.string.progress_waste,
                    DuplicateGroupAdapter.formatSize(progress.wasteSize)
                )
                tvStatusText.text = progress.phase

                if (progress.phase == "扫描完成") {
                    onScanComplete()
                } else if (progress.phase == "扫描被用户取消") {
                    onScanCancelled()
                }
            }
        }
    }

    /**
     * 扫描完成处理
     */
    private fun onScanComplete() {
        scanJob = null
        duplicateGroups = scanner.getLastResult()
        progressBar.isIndeterminate = false
        progressBar.progress = progressBar.max

        if (duplicateGroups.isEmpty()) {
            cardStatus.visibility = View.GONE
            llEmptyState.visibility = View.VISIBLE
            tvEmptyText.text = getString(R.string.hint_no_duplicates)
            btnStartScan.visibility = View.VISIBLE
            return
        }

        tvStatusText.text = getString(
            R.string.hint_scan_done
        )
        applySort()

        // 显示结果
        llEmptyState.visibility = View.GONE
        recyclerView.visibility = View.VISIBLE
        llActionBar.visibility = View.VISIBLE
        cardStatus.visibility = View.VISIBLE
    }

    /**
     * 扫描被取消
     */
    private fun onScanCancelled() {
        scanJob = null
        cardStatus.visibility = View.GONE
        llEmptyState.visibility = View.VISIBLE
        tvEmptyText.text = getString(R.string.hint_scan_cancelled)
        btnStartScan.visibility = View.VISIBLE
    }

    // ======================== 排序 ========================

    /**
     * 应用当前排序方式并刷新列表
     */
    private fun applySort() {
        if (duplicateGroups.isEmpty()) return

        val sorted = when (currentSort) {
            SortMode.SIZE_DESC -> duplicateGroups.sortedByDescending { it.fileSize }
            SortMode.SIZE_ASC -> duplicateGroups.sortedBy { it.fileSize }
            SortMode.COUNT_DESC -> duplicateGroups.sortedByDescending { it.files.size }
            SortMode.COUNT_ASC -> duplicateGroups.sortedBy { it.files.size }
            SortMode.NAME_ASC -> duplicateGroups.sortedBy { it.fileName.lowercase() }
            SortMode.NAME_DESC -> duplicateGroups.sortedByDescending { it.fileName.lowercase() }
            SortMode.WASTE_DESC -> duplicateGroups.sortedByDescending { it.wasteSize() }
        }

        adapter = DuplicateGroupAdapter(sorted) {
            updateDeleteButton()
        }
        recyclerView.adapter = adapter
        updateDeleteButton()
    }

    // ======================== 自动选择 ========================

    /**
     * 自动选择: 对所有组执行"保留最新，标记其他删除"策略
     */
    private fun onAutoSelect() {
        if (duplicateGroups.isEmpty()) return
        duplicateGroups.forEach { it.autoSelectKeepNewest() }
        adapter?.notifyDataSetChanged()
        updateDeleteButton()
        Toast.makeText(this, "已自动选择: 每组保留最新文件", Toast.LENGTH_SHORT).show()
    }

    // ======================== 删除 ========================

    /**
     * 更新删除按钮文本
     */
    private fun updateDeleteButton() {
        val totalDelete = duplicateGroups.sumOf { it.deleteCount() }
        val totalWaste = duplicateGroups.sumOf { it.wasteSize() }
        btnDelete.text = if (totalDelete > 0) {
            "删除选中 ($totalDelete 个, ${DuplicateGroupAdapter.formatSize(totalWaste)})"
        } else {
            getString(R.string.btn_delete_selected)
        }
    }

    /**
     * 执行删除操作
     * 弹出确认对话框后批量删除
     */
    private fun onDelete() {
        val filesToDelete = mutableListOf<FileEntry>()
        for (group in duplicateGroups) {
            filesToDelete.addAll(group.files.filter { it.markedForDeletion })
        }

        if (filesToDelete.isEmpty()) {
            Toast.makeText(this, "没有选择要删除的文件", Toast.LENGTH_SHORT).show()
            return
        }

        val totalSize = filesToDelete.sumOf { it.file.length() }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_confirm_title)
            .setMessage(
                getString(
                    R.string.delete_confirm_msg,
                    filesToDelete.size,
                    DuplicateGroupAdapter.formatSize(totalSize)
                )
            )
            .setPositiveButton(android.R.string.ok) { _, _ ->
                performDelete(filesToDelete, totalSize)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * 实际执行删除操作
     *
     * @param filesToDelete 要删除的文件列表
     * @param totalSize 总大小 (用于提示)
     */
    private fun performDelete(filesToDelete: List<FileEntry>, totalSize: Long) {
        lifecycleScope.launch {
            var successCount = 0
            var failCount = 0

            val result = withContext(Dispatchers.IO) {
                for (entry in filesToDelete) {
                    try {
                        if (entry.file.delete()) {
                            successCount++
                        } else {
                            failCount++
                            Log.w(TAG, "删除失败 (返回false): ${entry.path}")
                        }
                    } catch (e: Exception) {
                        failCount++
                        Log.e(TAG, "删除异常: ${entry.path}", e)
                    }
                }
                Pair(successCount, failCount)
            }

            val (success, fail) = result
            val msg = when {
                fail == 0 -> getString(R.string.delete_success, success,
                    DuplicateGroupAdapter.formatSize(totalSize))
                else -> getString(R.string.delete_partial, success, fail)
            }

            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()

            // 刷新列表: 保留未标记删除的文件，以及删除失败（文件仍存在）的标记项；
            // 移除删除成功（文件已不存在）的标记项。
            val updatedGroups = mutableListOf<DuplicateGroup>()
            for (group in duplicateGroups) {
                val remaining = group.files.filter { !it.markedForDeletion || it.file.exists() }
                if (remaining.size >= 2) {
                    updatedGroups.add(group.copy(files = remaining.toMutableList()))
                }
            }
            duplicateGroups = updatedGroups

            if (duplicateGroups.isEmpty()) {
                // 全部清理完毕
                cardStatus.visibility = View.GONE
                recyclerView.visibility = View.GONE
                llActionBar.visibility = View.GONE
                llEmptyState.visibility = View.VISIBLE
                tvEmptyText.text = getString(R.string.hint_no_duplicates)
                btnStartScan.visibility = View.VISIBLE
            } else {
                applySort()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scanJob?.cancel()
    }
}