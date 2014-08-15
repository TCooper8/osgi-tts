package com.cooper.osgi.speech.service

import org.apache.commons.collections.map.LRUMap
import scala.collection.mutable
import scala.collection.JavaConversions._
import scala.util.{Success, Try}

/**
 * Naive scala wrapper for an LRUMap.
 * @param maxSize The maximum map size.
 * @tparam A Generic key type.
 * @tparam B Generic value type.
 */
class LruMap[A, B](maxSize: Int) extends mutable.Map[A, B] {
	private[this] val map = new LRUMap(maxSize).asInstanceOf[java.util.AbstractMap[A, B]]

	def +=(kv: (A, B)) = {
		kv match {
			case (k, v) => Try{ map.put(k, v) }
		}
		this
	}

	def -=(key: A) = {
		val _ = Try{ map.remove(key) }
		this
	}

	def get(key: A): Option[B] =
		Try{ map.get(key) } match {
			case Success(v) => Option(v)
			case _ => None
		}

	def iterator: Iterator[(A, B)] = map.iterator
}

class SynchronizedLruMap[A, B](maxSize: Int)
  extends LruMap[A, B](maxSize) with mutable.SynchronizedMap[A, B]
