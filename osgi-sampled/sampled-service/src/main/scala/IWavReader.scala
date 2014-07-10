package com.cooper.osgi.sampled.service

import com.cooper.osgi.sampled.IAudioReader

abstract class IWavReader extends IAudioReader(".wav") {
	val chunkID: String
	val chunkSize: Int
	val format: String
	val fmtChunkID: String
	val fmtChunkSize: Int
	val audioFormat: Short
	val numChannels: Short
	val sampleRate: Int
	val byteRate: Int
	val blockAlign: Short
	val bitsPerSample: Short
	val dataChunkID: String
	val dataChunkSize: Int

	/**
	 * Compares the format of two IWavReaders, for validation of child/parent relationship in sequencing.
	 * @param wav The IWavReaders to compare format data.
	 * @return A boolean, true if the formats are compatible, else false.
	 */
	def compareFormat(wav: IWavReader): Boolean = {
		chunkID == wav.chunkID &&
		  format == wav.format &&
		  fmtChunkID == wav.fmtChunkID &&
		  fmtChunkSize == wav.fmtChunkSize &&
		  audioFormat == wav.audioFormat &&
		  numChannels == wav.numChannels &&
		  sampleRate == wav.sampleRate &&
		  byteRate == wav.byteRate &&
		  bitsPerSample == wav.bitsPerSample
		dataChunkID == wav.dataChunkID
	}
}
