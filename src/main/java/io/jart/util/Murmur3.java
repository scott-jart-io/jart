/*
 * Copyright (C) 2011 The Guava Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

/*
 * MurmurHash3 was written by Austin Appleby, and is placed in the public
 * domain. The author hereby disclaims copyright to this source code.
 */

/*
 * Source:
 * http://code.google.com/p/smhasher/source/browse/trunk/MurmurHash3.cpp
 * (Modified to adapt to Guava coding conventions and to use the HashFunction interface)
 */

package io.jart.util;

/**
 * See http://smhasher.googlecode.com/svn/trunk/MurmurHash3.cpp
 * MurmurHash3_x86_32 helper class
 *
 * @author Austin Appleby
 * @author Dimitris Andreou
 * @author Kurt Alfred Kluever
 */

public final class Murmur3 {
	private static final int seed = 0;
	private static final int C1 = 0xcc9e2d51;
	private static final int C2 = 0x1b873593;

	private Murmur3() {} // hide constructor
	
	/**
	 * Hash int. 0 always hashes to 0.
	 *
	 * @param input the input
	 * @return the hash code
	 */
	public static int hashInt(int input){
		if(input == 0) return 0;
		int k1 = mixK1(input);
		int h1 = mixH1(seed, k1);

		return fmix(h1, 4);
	}

	/**
	 * Hash long. 0 always hashes to 0.
	 *
	 * @param input the input
	 * @return the hash code
	 */
	public static int hashLong(long input){
		if(input == 0) return 0;
		int low = (int) input;
		int high = (int) (input >>> 32);

		int k1 = mixK1(low);
		int h1 = mixH1(seed, k1);

		k1 = mixK1(high);
		h1 = mixH1(h1, k1);

		return fmix(h1, 8);
	}

	/**
	 * Hash unencoded chars.
	 *
	 * @param input the input
	 * @return the int
	 */
	public static int hashUnencodedChars(CharSequence input){
		int h1 = seed;

		// step through the CharSequence 2 chars at a time
		for(int i = 1; i < input.length(); i += 2)
		{
			int k1 = input.charAt(i - 1) | (input.charAt(i) << 16);
			k1 = mixK1(k1);
			h1 = mixH1(h1, k1);
		}

		// deal with any remaining characters
		if((input.length() & 1) == 1)
		{
			int k1 = input.charAt(input.length() - 1);
			k1 = mixK1(k1);
			h1 ^= k1;
		}

		return fmix(h1, 2 * input.length());
	}

	/**
	 * Mix K 1.
	 *
	 * @param k1 the k 1
	 * @return the int
	 */
	public static int mixK1(int k1){
		k1 *= C1;
		k1 = Integer.rotateLeft(k1, 15);
		k1 *= C2;
		return k1;
	}

	/**
	 * Mix H 1.
	 *
	 * @param h1 the h 1
	 * @param k1 the k 1
	 * @return the int
	 */
	public static int mixH1(int h1, int k1){
		h1 ^= k1;
		h1 = Integer.rotateLeft(h1, 13);
		h1 = h1 * 5 + 0xe6546b64;
		return h1;
	}

	/**
	 * Fmix.
	 *
	 * @param h1 the h 1
	 * @param length the length
	 * @return the int
	 */
	// Finalization mix - force all bits of a hash block to avalanche
	public static int fmix(int h1, int length){
		h1 ^= length;
		h1 ^= h1 >>> 16;
			h1 *= 0x85ebca6b;
			h1 ^= h1 >>> 13;
			h1 *= 0xc2b2ae35;
			h1 ^= h1 >>> 16;
			return h1;
	}

	/**
	 * Finalization mix - force all bits of a hash block to avalanche (java-specific).
	 *
	 * @param h1 the h 1
	 * @param length the length
	 * @return the int
	 */
	public static int fmixj(int h1, int length){
		h1 ^= length;
		h1 ^= h1 >>> 16;
		h1 *= 0x85ebca6b;
		h1 ^= h1 >>> 13;
		h1 *= 0xc2b2ae35;
		// omit last xor as java hashmap does something similar --
		// quality of hash appears better WITHOUT this line assuming
		// the extra hashmap stuff
		return h1;
	}

}
