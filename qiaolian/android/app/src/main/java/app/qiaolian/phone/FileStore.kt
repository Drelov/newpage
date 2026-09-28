package app.qiaolian.phone

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream

class FileStore(root: File) {
    private val rootDir: File = root.canonicalFile

    fun list(path: String): JSONObject {
        val clean = cleanRel(path)
        val dir = resolve(clean)
        if (!dir.exists()) throw FileNotFoundException("文件不存在")
        if (!dir.isDirectory) throw IllegalArgumentException("不是文件夹")
        val children = dir.listFiles() ?: throw SecurityException("没有权限")
        val entries = JSONArray()
        val sorted = children.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
        var truncated = false
        for (child in sorted) {
            if (entries.length() >= 5000) {
                truncated = true
                break
            }
            val item = JSONObject()
            item.put("name", child.name)
            item.put("dir", child.isDirectory)
            item.put("size", if (child.isDirectory) 0 else child.length())
            item.put("mtime", child.lastModified() / 1000)
            entries.put(item)
        }
        val out = JSONObject()
        out.put("path", clean)
        out.put("entries", entries)
        if (truncated) out.put("truncated", true)
        return out
    }

    fun openRead(path: String): Pair<FileInputStream, Long> {
        val file = resolve(cleanRel(path))
        if (!file.exists()) throw FileNotFoundException("文件不存在")
        if (file.isDirectory) throw IllegalArgumentException("这是文件夹")
        return FileInputStream(file) to file.length()
    }

    fun createPart(path: String): Triple<String, File, FileOutputStream> {
        val clean = cleanRel(path)
        if (clean.isEmpty()) throw IllegalArgumentException("路径无效")
        val dest = resolve(clean)
        dest.parentFile?.mkdirs()
        val part = File(dest.parentFile, dest.name + ".qiaolian.part")
        return Triple(clean, part, FileOutputStream(part, false))
    }

    fun finishPart(clean: String, part: File): Long {
        val dest = resolve(clean)
        if (dest.exists()) dest.delete()
        if (!part.renameTo(dest)) {
            part.copyTo(dest, overwrite = true)
            part.delete()
        }
        return dest.length()
    }

    fun mkdir(path: String) {
        val clean = cleanRel(path)
        if (clean.isEmpty()) throw IllegalArgumentException("路径无效")
        if (!resolve(clean).mkdirs() && !resolve(clean).isDirectory) {
            throw IllegalStateException("无法创建文件夹")
        }
    }

    fun delete(path: String) {
        val clean = cleanRel(path)
        if (clean.isEmpty()) throw IllegalArgumentException("路径无效")
        val file = resolve(clean)
        if (!file.exists()) throw FileNotFoundException("文件不存在")
        if (!file.deleteRecursively()) throw IllegalStateException("无法删除")
    }

    fun rename(from: String, to: String) {
        val srcRel = cleanRel(from)
        val dstRel = cleanRel(to)
        if (srcRel.isEmpty() || dstRel.isEmpty()) throw IllegalArgumentException("路径无效")
        val src = resolve(srcRel)
        val dst = resolve(dstRel)
        if (!src.exists()) throw FileNotFoundException("文件不存在")
        dst.parentFile?.mkdirs()
        if (!src.renameTo(dst)) throw IllegalStateException("无法重命名")
    }

    private fun resolve(clean: String): File {
        val full = if (clean.isEmpty()) rootDir else File(rootDir, clean).canonicalFile
        val rootPath = rootDir.path
        val fullPath = full.path
        if (fullPath != rootPath && !fullPath.startsWith(rootPath + File.separator)) {
            throw IllegalArgumentException("路径无效")
        }
        return full
    }

    companion object {
        fun cleanRel(path: String): String {
            val normalized = path.trim().replace('\\', '/')
            if (normalized.length > 1024 || normalized.indexOf('\u0000') >= 0) {
                throw IllegalArgumentException("路径无效")
            }
            val parts = ArrayList<String>()
            for (part in normalized.split('/')) {
                if (part.isEmpty() || part == ".") continue
                if (part == "..") throw IllegalArgumentException("路径无效")
                parts.add(part)
            }
            return parts.joinToString("/")
        }
    }
}
