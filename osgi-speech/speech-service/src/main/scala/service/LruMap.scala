package com.cooper.osgi.speech.service

import org.apache.commons.collections.map.LRUMap
import scala.collection.mutable
import scala.collection.JavaConversions._
import scala.util.{Success, Try}

/**
 * Naive scala wrapper for an LRUMap.
 * @param maxSizeIn The maximum map size.
 * @tparam A Generic key type.
 * @tparam B Generic value type.
 */
class LruMap[A, B](maxSizeIn: Int) extends mutable.Map[A, B] {
	private[this] var curMaxSize = maxSizeIn
	private[this] var map = new LRUMap(maxSizeIn).asInstanceOf[java.util.AbstractMap[A, B]]

	override def put(key: A, value: B): Option[B] =
		Option{ this.map.put(key, value) }

	override def clear(): Unit = map.clear()

	override def contains(key: A): Boolean = map.contains(key)

	override def size: Int = map.size()

	/**
	 * Represents the maximum size or the LruMap.
	 * @return
	 */
	def maxSize = curMaxSize

	/**
	 * Resizes the LruMap with a new maximum size, copying over the old data.
	 * @param maxSize The new max size to use.
	 */
	def resize(maxSize: Int) {
		val newMap = new LRUMap(maxSize)

		map.foreach { case (k, v) =>
			if (newMap.size() < newMap.maxSize())
				newMap.put(k, v)
			else ()
		}

		this.map = newMap.asInstanceOf[java.util.AbstractMap[A, B]]
		this.curMaxSize = maxSize
	}

	override def +=(kv: (A, B)) = {
		kv match {
			case (k, v) => Try{ map.put(k, v) }
		}
		this
	}

	override def -=(key: A) = {
		val _ = Try{ map.remove(key) }
		this
	}

	override def get(key: A): Option[B] =
		Try{ map.get(key) } match {
			case Success(v) => Option(v)
			case _ => None
		}

	override def iterator: Iterator[(A, B)] = map.iterator
}

class SynchronizedLruMap[A, B](maxSizeIn: Int)
  extends LruMap[A, B](maxSizeIn) with mutable.SynchronizedMap[A, B] {

	override def maxSize =
		this.synchronized(super.maxSize)

	override def resize(maxSize: Int) {
		this.synchronized(super.resize(maxSize))
	}
}
