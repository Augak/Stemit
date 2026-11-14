package com.stemit.app

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.roundToInt

/** PCM WAV editor for Stemit's 16-bit WAV stems. */
object WavEditor {
    data class Header(val dataOffset:Long,val dataBytes:Long,val sampleRate:Int,val channels:Int)
    fun inspect(file:File):Header {
        RandomAccessFile(file,"r").use { raf ->
            require(raf.length()>=44) { "This file is too small to be a WAV file." }
            require(readAscii(raf,0,4)=="RIFF" && readAscii(raf,8,4)=="WAVE") { "Choose a PCM WAV file." }
            var offset=12L; var fmt:ByteArray?=null; var dataOffset=-1L; var dataSize=-1L
            while(offset+8<=raf.length()) {
                val id=readAscii(raf,offset,4); val size=readU32(raf,offset+4)
                if(id=="fmt ") { val n=size.coerceAtMost(64).toInt(); fmt=ByteArray(n); raf.seek(offset+8); raf.readFully(fmt!!) }
                else if(id=="data") { dataOffset=offset+8; dataSize=size; break }
                offset+=8+size+(size and 1L)
            }
            require(fmt!=null && dataOffset>=0) { "WAV header is incomplete." }
            val f=fmt!!; val format=u16(f,0); val channels=u16(f,2); val rate=u32(f,4).toInt(); val bits=u16(f,14)
            require(format==1 && bits==16 && channels in 1..2) { "Studio editing supports 16-bit PCM mono or stereo WAV files." }
            require(dataOffset+dataSize<=raf.length()) { "WAV audio data is incomplete." }
            return Header(dataOffset,dataSize,rate,channels)
        }
    }
    fun edit(input:File,output:File,startSeconds:Double,endSeconds:Double,gain:Float) {
        val h=inspect(input); val frameBytes=h.channels*2; val total=h.dataBytes/frameBytes
        val start=(startSeconds*h.sampleRate).toLong().coerceIn(0,total); val end=(endSeconds*h.sampleRate).toLong().coerceIn(start,total)
        require(end>start) { "End time must be after start time." }
        val bytes=(end-start)*frameBytes; require(bytes<=0xFFFFFFFFL-36) { "Selected WAV is too large." }
        RandomAccessFile(input,"r").use { src -> RandomAccessFile(output,"rw").use { dst ->
            dst.setLength(0); dst.writeBytes("RIFF"); writeU32(dst,36+bytes); dst.writeBytes("WAVE")
            dst.writeBytes("fmt "); writeU32(dst,16); writeU16(dst,1); writeU16(dst,h.channels)
            writeU32(dst,h.sampleRate.toLong()); writeU32(dst,(h.sampleRate*frameBytes).toLong()); writeU16(dst,frameBytes); writeU16(dst,16)
            dst.writeBytes("data"); writeU32(dst,bytes); src.seek(h.dataOffset+start*frameBytes)
            val buffer=ByteArray(64*1024); var remaining=bytes
            while(remaining>0) {
                val count=minOf(buffer.size.toLong(),remaining).toInt(); src.readFully(buffer,0,count)
                if(gain!=1f) { var i=0; while(i+1<count) {
                    val sample=((buffer[i].toInt() and 255) or (buffer[i+1].toInt() shl 8)).toShort().toInt()
                    val adjusted=(sample*gain).roundToInt().coerceIn(-32768,32767)
                    buffer[i]=adjusted.toByte(); buffer[i+1]=(adjusted shr 8).toByte(); i+=2
                } }
                dst.write(buffer,0,count); remaining-=count
            }
            dst.fd.sync()
        } }
    }
    private fun readAscii(r:RandomAccessFile,p:Long,n:Int):String { val b=ByteArray(n); r.seek(p); r.readFully(b); return String(b,Charsets.US_ASCII) }
    private fun readU32(r:RandomAccessFile,p:Long):Long { r.seek(p); return (r.read() and 255).toLong() or ((r.read() and 255).toLong() shl 8) or ((r.read() and 255).toLong() shl 16) or ((r.read() and 255).toLong() shl 24) }
    private fun u16(b:ByteArray,p:Int)=(b[p].toInt() and 255) or ((b[p+1].toInt() and 255) shl 8)
    private fun u32(b:ByteArray,p:Int)=u16(b,p).toLong() or (u16(b,p+2).toLong() shl 16)
    private fun writeU16(r:RandomAccessFile,v:Int) { r.write(v and 255); r.write((v ushr 8) and 255) }
    private fun writeU32(r:RandomAccessFile,v:Long) { repeat(4) { r.write((v ushr (8*it)).toInt() and 255) } }
}
