package com.cleaner.app

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import net.jpountz.xxhash.XXHashFactory
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import kotlin.system.measureTimeMillis

/**
 * 文件扫描引擎
 *
 * 采用三级比较策略:
 * 1. 文件大小过滤 - 大小不同直接排除
 * 2. XXH64 快速哈希初筛 - 读取前 64KB 计算，速度极快
 * 3. SHA-256 精确确认 - 对 XXH64 匹配的文件完整计算
 *
 * @param context 应用上下文
 */
class FileScanner(private val context: Context) {

    companion object {
        private const val TAG = "FileScanner"
        /** XXH64 采样大小: 读取文件前 64KB 用于快速比较 */
        private const val QUICK_HASH_SIZE = 64 * 1024L
        /** SHA-256 分块读取大小: 1MB */
        private const val SHA_BUFFER_SIZE = 1024 * 1024
    }

    /** XXH64 工厂实例 (线程安全) */
    private val xxHashFactory = XXHashFactory.fastestInstance()

    /**
     * 扫描进度数据
     *
     * @property filesScanned 已扫描文件数
     * @property groupsFound 已发现的重复组数
     * @property wasteSize 可释放空间 (字节)
     * @property currentPath 当前正在扫描的文件路径
     * @property phase 当前阶段描述
     */
    data class ScanProgress(
        val filesScanned: Int,
        val groupsFound: Int,
        val wasteSize: Long,
        val currentPath: String = "",
        val phase: String = ""
    )

    /**
     * 获取所有可扫描的根目录
     *
     * 在拥有 MANAGE_EXTERNAL_STORAGE 权限后，通过 Volume 获取存储路径
     *
     * @return 根目录列表
     */
    fun getScanRoots(): List<File> {
        val roots = mutableListOf<File>()
        // Track canonical paths to prevent adding the same physical directory
        // multiple times via different symlink paths (e.g. /sdcard → /storage/emulated/0)
        val canonicalPaths = mutableSetOf<String>()

        fun addIfUnique(dir: File) {
            if (!dir.exists() || !dir.canRead()) return
            val canonical = try { dir.canonicalPath } catch (_: Exception) { dir.absolutePath }
            if (canonical !in canonicalPaths) {
                canonicalPaths.add(canonical)
                roots.add(dir)
            }
        }

        // 内部存储
        val internalDirs = listOfNotNull(
            Environment.getExternalStorageDirectory(),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).parentFile
        )
        for (dir in internalDirs) {
            addIfUnique(dir)
        }

        // 尝试通过 MediaStore 获取外部存储
        try {
            val externalVolumes: Array<out File> = context.getExternalFilesDirs(null)
            for (vol in externalVolumes) {
                if (vol != null) {
                    // 从 /storage/emulated/0/Android/data/... 取 /storage/emulated/0/
                    val storageRoot = vol.parentFile?.parentFile?.parentFile
                    if (storageRoot != null) {
                        addIfUnique(storageRoot)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "获取外部存储失败: ${e.message}")
        }

        // 标准外部存储路径（部分设备通过不同挂载点访问同一存储）
        val knownPaths = listOf(
            "/storage/emulated/0",
            "/sdcard",
            "/mnt/sdcard"
        )
        for (path in knownPaths) {
            addIfUnique(File(path))
        }

        return roots
    }

    /**
     * 获取指定目录下可扫描的子目录列表（仅一层）
     *
     * @param root 根目录
     * @return 可访问的子目录列表
     */
    fun getSubDirectories(root: File): List<File> {
        if (!root.exists() || !root.canRead()) return emptyList()
        return root.listFiles()
            ?.filter { it.isDirectory && it.canRead() && !it.name.startsWith(".") }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
    }

    /**
     * 统计指定目录树中的文件总数
     *
     * @param roots 要统计的根目录列表
     * @param onProgress 进度回调 (已统计目录数)
     * @return 文件总数
     */
    fun countFiles(roots: List<File>, onProgress: ((Int) -> Unit)? = null): Int {
        var count = 0
        var dirsScanned = 0
        for (root in roots) {
            root.walkTopDown()
                .onEnter { dir ->
                    // 跳过隐藏目录、系统目录
                    if (dir.name.startsWith(".") ||
                        dir.absolutePath.contains("/Android/data/") ||
                        dir.absolutePath.contains("/Android/obb/")
                    ) return@onEnter false
                    true
                }
                .forEach { file ->
                    if (file.isFile && file.canRead()) {
                        count++
                    } else if (file.isDirectory) {
                        dirsScanned++
                        onProgress?.invoke(dirsScanned)
                    }
                }
        }
        return count
    }

    /**
     * 扫描重复文件
     *
     * @param roots 要扫描的根目录列表
     * @return 扫描进度 Flow
     */
    fun scanDuplicates(roots: List<File>): Flow<ScanProgress> = flow {
        emit(ScanProgress(0, 0, 0, phase = "准备扫描..."))

        // 阶段1: 收集文件信息 (大小 + 路径)
        val sizeMap = LinkedHashMap<Long, MutableList<File>>()
        var filesScanned = 0

        for (root in roots) {
            root.walkTopDown()
                .maxDepth(20)  // 防止符号链接循环导致 StackOverflowError
                .onEnter { dir ->
                    // 跳过隐藏目录和系统目录
                    if (dir.name.startsWith(".") ||
                        dir.absolutePath.contains("/Android/data/") ||
                        dir.absolutePath.contains("/Android/obb/")
                    ) return@onEnter false
                    true
                }
                .forEach { file ->
                    // 检查取消标志
                    if (Thread.currentThread().isInterrupted) {
                        Log.i(TAG, "扫描被用户取消")
                        return@flow
                    }

                    if (file.isFile && file.canRead() && file.length() > 0) {
                        val size = file.length()
                        sizeMap.getOrPut(size) { mutableListOf() }.add(file)
                        filesScanned++
                        if (filesScanned % 200 == 0) {
                            emit(ScanProgress(filesScanned, 0, 0, file.absolutePath, "扫描文件中"))
                        }
                    }
                }
        }

        // 阶段2: 对大小相同的文件组计算 XXH64 快速哈希
        emit(ScanProgress(filesScanned, 0, 0, phase = "快速比对中..."))
        val candidates = mutableListOf<List<File>>()

        for ((_, files) in sizeMap) {
            if (files.size < 2) continue

            val xxhMap = HashMap<Long, MutableList<File>>()
            for (file in files) {
                if (Thread.currentThread().isInterrupted) return@flow
                val quickHash = computeXXH64(file)
                xxhMap.getOrPut(quickHash) { mutableListOf() }.add(file)
            }

            for ((_, matched) in xxhMap) {
                if (matched.size >= 2) {
                    candidates.add(matched)
                }
            }
        }

        // 阶段3: 对 XXH64 匹配的文件计算 SHA-256 精确确认
        emit(ScanProgress(filesScanned, 0, 0, phase = "精确比对中..."))
        val result = mutableListOf<DuplicateGroup>()
        var processedCandidates = 0

        for (candidateGroup in candidates) {
            if (Thread.currentThread().isInterrupted) return@flow

            val shaMap = HashMap<String, MutableList<File>>()
            for (file in candidateGroup) {
                if (Thread.currentThread().isInterrupted) return@flow
                val sha = computeSHA256(file)
                shaMap.getOrPut(sha) { mutableListOf() }.add(file)
            }

            for ((hash, duplicates) in shaMap) {
                if (duplicates.size >= 2) {
                    val group = DuplicateGroup(
                        hash = hash,
                        fileName = duplicates.first().name,
                        fileSize = duplicates.first().length(),
                        files = duplicates.map { f ->
                            FileEntry(
                                file = f,
                                markedForDeletion = false,
                                lastModified = f.lastModified()
                            )
                        }.toMutableList()
                    )
                    // 自动选择: 保留最新的
                    group.autoSelectKeepNewest()
                    result.add(group)
                }
            }

            processedCandidates++
            if (processedCandidates % 10 == 0 || processedCandidates == candidates.size) {
                val totalWaste = result.sumOf { it.wasteSize() }
                emit(ScanProgress(filesScanned, result.size, totalWaste, phase = "精确比对中"))
            }
        }

        // 保存结果到实例变量供外部获取
        this@FileScanner._lastResult = result
        val totalWaste = result.sumOf { it.wasteSize() }
        emit(ScanProgress(filesScanned, result.size, totalWaste, phase = "扫描完成"))
    }.flowOn(Dispatchers.IO)

    /** 最近一次扫描结果 */
    @Volatile
    private var _lastResult: List<DuplicateGroup> = emptyList()

    /**
     * 获取最近一次扫描结果
     *
     * @return 重复文件组列表
     */
    fun getLastResult(): List<DuplicateGroup> = _lastResult

    /**
     * 计算文件前 QUICK_HASH_SIZE 字节的 XXH64 哈希
     *
     * @param file 目标文件
     * @return XXH64 哈希值
     */
    private fun computeXXH64(file: File): Long {
        val instance = xxHashFactory.newStreamingHash64(0L)
        val buffer = ByteArray(8192)
        var remaining = QUICK_HASH_SIZE
        FileInputStream(file).use { fis ->
            while (remaining > 0) {
                val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                val read = fis.read(buffer, 0, toRead)
                if (read <= 0) break
                instance.update(buffer, 0, read)
                remaining -= read
            }
        }
        return instance.value
    }

    /**
     * 计算文件的 SHA-256 完整哈希
     *
     * @param file 目标文件
     * @return SHA-256 十六进制字符串
     */
    private fun computeSHA256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(SHA_BUFFER_SIZE)
        FileInputStream(file).use { fis ->
            var read: Int
            while (fis.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}