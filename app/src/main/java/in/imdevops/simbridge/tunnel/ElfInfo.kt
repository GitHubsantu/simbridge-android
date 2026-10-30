package `in`.imdevops.simbridge.tunnel

/** Just enough of an ELF header to tell which CPU and word size an executable was built for. */
data class ElfInfo(val is64Bit: Boolean, val machine: Int, val type: Int) {
    val architecture: String
        get() = when (machine) {
            0x28 -> "ARM"
            0xB7 -> "AArch64"
            0x03 -> "x86"
            0x3E -> "x86-64"
            else -> "unknown(0x${machine.toString(16)})"
        }

    /** Android ABI names that can run this binary. */
    val compatibleAbis: List<String>
        get() = when (machine) {
            0x28 -> listOf("armeabi-v7a", "armeabi")
            0xB7 -> listOf("arm64-v8a")
            0x03 -> listOf("x86")
            0x3E -> listOf("x86_64")
            else -> emptyList()
        }

    companion object {
        /** Needs the first 20 bytes. Returns null if this is not a little-endian ELF file. */
        fun parse(header: ByteArray): ElfInfo? {
            if (header.size < 20) return null
            if (header[0] != 0x7F.toByte() || header[1] != 'E'.code.toByte() ||
                header[2] != 'L'.code.toByte() || header[3] != 'F'.code.toByte()
            ) return null
            val elfClass = header[4].toInt()
            if (elfClass != 1 && elfClass != 2) return null
            if (header[5].toInt() != 1) return null // little-endian only
            return ElfInfo(elfClass == 2, u16(header, 18), u16(header, 16))
        }

        private fun u16(b: ByteArray, offset: Int) =
            (b[offset].toInt() and 0xFF) or ((b[offset + 1].toInt() and 0xFF) shl 8)
    }
}

enum class BinaryVerdict { OK, MISSING, NOT_ELF, WRONG_ARCH }

object BinaryCheck {
    fun verdict(present: Boolean, elf: ElfInfo?, supportedAbis: List<String>): BinaryVerdict = when {
        !present -> BinaryVerdict.MISSING
        elf == null -> BinaryVerdict.NOT_ELF
        elf.compatibleAbis.none { it in supportedAbis } -> BinaryVerdict.WRONG_ARCH
        else -> BinaryVerdict.OK
    }
}
