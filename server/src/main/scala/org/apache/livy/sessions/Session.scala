/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.livy.sessions

import java.io.InputStream
import java.net.{URI, URISyntaxException}
import java.security.PrivilegedExceptionAction
import java.util.concurrent.{Executors, LinkedBlockingQueue, ThreadFactory, ThreadPoolExecutor, TimeUnit}
import java.util.UUID

import scala.concurrent.{ExecutionContext, Future}

import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.hadoop.fs.permission.FsPermission
import org.apache.hadoop.security.UserGroupInformation

import org.apache.livy.{LivyConf, Logging, Utils}
import org.apache.livy.utils.AppInfo

object Session {
  trait RecoveryMetadata { val id: Int }

  val BLACKLIST_CUSTOM_CLASSPATH: Set[String] = Set("spark.submit.deployMode",
    "spark.submit.proxyUser.allowCustomClasspathInClusterMode")

  lazy val configBlackList: Set[String] = {
    val url = getClass.getResource("/spark-blacklist.conf")
    if (url != null) Utils.loadProperties(url).keySet else Set()
  }

  /**
   * Validates and prepares a user-provided configuration for submission.
   *
   * - Verifies that no blacklisted configurations are provided.
   * - Merges file lists in the configuration with the explicit lists provided in the request
   * - Resolve file URIs to make sure they reference the default FS
   * - Verify that file URIs don't reference non-whitelisted local resources
   */
  def prepareConf(
      conf: Map[String, String],
      jars: Seq[String],
      files: Seq[String],
      archives: Seq[String],
      pyFiles: Seq[String],
      livyConf: LivyConf): Map[String, String] = {
    if (conf == null) {
      return Map()
    }

    val errors = if (livyConf.getBoolean(LivyConf.SESSION_ALLOW_CUSTOM_CLASSPATH)) {
      conf.keySet.filter(configBlackList.contains)
    } else {
      conf.keySet.filter((configBlackList ++ BLACKLIST_CUSTOM_CLASSPATH).contains)
    }
    if (errors.nonEmpty) {
      throw new IllegalArgumentException(
        "Blacklisted configuration values in session config: " + errors.mkString(", "))
    }

    val confLists: Map[String, Seq[String]] = livyConf.sparkFileLists
      .map { key => (key -> Nil) }.toMap

    val userLists = confLists ++ Map(
      LivyConf.SPARK_JARS -> jars,
      LivyConf.SPARK_FILES -> files,
      LivyConf.SPARK_ARCHIVES -> archives,
      LivyConf.SPARK_PY_FILES -> pyFiles)

    val merged = userLists.flatMap { case (key, list) =>
      val confList = conf.get(key)
        .map { list =>
          resolveURIs(list.split("[, ]+").toSeq, livyConf)
        }
        .getOrElse(Nil)
      val userList = resolveURIs(list, livyConf)
      if (confList.nonEmpty || userList.nonEmpty) {
        Some(key -> (userList ++ confList).mkString(","))
      } else {
        None
      }
    }

    val masterConfList = Map(LivyConf.SPARK_MASTER -> livyConf.sparkMaster()) ++
      livyConf.sparkDeployMode().map(LivyConf.SPARK_DEPLOY_MODE -> _).toMap

    conf ++ masterConfList ++ merged
  }

  /**
   * Prepends the value of the "fs.defaultFS" configuration to any URIs that do not have a
   * scheme. URIs are required to at least be absolute paths.
   *
   * @throws IllegalArgumentException If an invalid URI is found in the given list.
   */
  def resolveURIs(uris: Seq[String], livyConf: LivyConf): Seq[String] = {
    val defaultFS = livyConf.hadoopConf.get("fs.defaultFS").stripSuffix("/")
    uris.filter(_.nonEmpty).map { _uri =>
      val uri = try {
        new URI(_uri)
      } catch {
        case e: URISyntaxException => throw new IllegalArgumentException(e)
      }
      resolveURI(uri, livyConf).toString()
    }
  }

  def resolveURI(uri: URI, livyConf: LivyConf): URI = {
    val defaultFS = livyConf.hadoopConf.get("fs.defaultFS").stripSuffix("/")
    val resolved =
      if (uri.getScheme() == null) {
        val pathWithSegment =
          if (uri.getFragment() != null) uri.getPath() + '#' + uri.getFragment() else uri.getPath()

        require(pathWithSegment.startsWith("/"), s"Path '${uri.getPath()}' is not absolute.")
        new URI(defaultFS + pathWithSegment)
      } else {
        uri
      }

    if (resolved.getScheme() == "file") {
      // Make sure the location is whitelisted before allowing local files to be added.
      require(livyConf.localFsWhitelist.find(resolved.getPath().startsWith).isDefined,
        s"Local path ${uri.getPath()} cannot be added to user sessions.")
    }

    resolved
  }
}

class NamedThreadFactory(prefix: String) extends ThreadFactory {
  private val defaultFactory = Executors.defaultThreadFactory()

  override def newThread(r: Runnable): Thread = {
    val thread = defaultFactory.newThread(r)
    thread.setName(prefix + "-" + thread.getName)
    thread
  }
}

abstract class Session(
    val id: Int,
    val name: Option[String],
    val owner: String,
    val ttl: Option[String],
    val idleTimeout: Option[String],
    val livyConf: LivyConf)
  extends Logging {

  def this(id: Int,
   name: Option[String],
   owner: String,
   livyConf: LivyConf) {
    this(id, name, owner, None, None, livyConf)
  }

  import Session._

  protected val sessionManageExecutors: ExecutionContext = {
    val poolSize = livyConf.getInt(LivyConf.SESSION_MANAGE_THREADS)
    val poolQueueSize = livyConf.getInt(LivyConf.SESSION_MANAGE_WAIT_QUEUE_SIZE)
    val keepAliveTime = livyConf.getTimeAsMs(
      LivyConf.SESSION_MANAGE_KEEPALIVE_TIME) / 1000
    debug(s"Background session manage executors with size=${poolSize}," +
      s" wait queue size= ${poolQueueSize}, keepalive time ${keepAliveTime} seconds")
    val queue = new LinkedBlockingQueue[Runnable](poolQueueSize)
    val executor = new ThreadPoolExecutor(
      poolSize,
      poolSize,
      keepAliveTime,
      TimeUnit.SECONDS,
      queue,
      new NamedThreadFactory("LivyServer2-SessionManageExecutors"))
    executor.allowCoreThreadTimeOut(true)
    ExecutionContext.fromExecutorService(executor)
  }

  protected implicit val executionContext = ExecutionContext.global

  // validate session name. The name should not be a number
  name.foreach { sessionName =>
    if (sessionName.forall(_.isDigit)) {
      throw new IllegalArgumentException(s"Invalid session name: $sessionName")
    }
  }

  protected var _appId: Option[String] = None

  private var _lastActivity = System.nanoTime()

  var startedOn : Option[Long] = None

  // Directory where the session's staging files are created. The directory is only accessible
  // to the session's effective user.
  private var stagingDir: Path = null

  def appId: Option[String] = _appId

  var appInfo: AppInfo = AppInfo()


  def lastActivity: Long = state match {
    case SessionState.Error(time) => time
    case SessionState.Dead(time) => time
    case SessionState.Success(time) => time
    case _ => _lastActivity
  }

  def logLines(): IndexedSeq[String]

  def recordActivity(): Unit = {
    _lastActivity = System.nanoTime()
  }

  def recoveryMetadata: RecoveryMetadata

  def state: SessionState

  def start(): Unit

  def stop(): Future[AnyVal] = Future {
    try {
      info(s"Stopping $this...")
      stopSession()
      info(s"Stopped $this.")
    } catch {
      case e: Exception =>
        warn(s"Error stopping session $id.", e)
    }

    try {
      if (stagingDir != null) {
        debug(s"Deleting session $id staging directory $stagingDir")
        doAsOwner {
          val fs = FileSystem.newInstance(livyConf.hadoopConf)
          try {
            fs.delete(stagingDir, true)
          } finally {
            fs.close()
          }
        }
      }
    } catch {
      case e: Exception =>
        warn(s"Error cleaning up session $id staging dir.", e)
    }
  }(sessionManageExecutors)


  override def toString(): String = s"${this.getClass.getSimpleName} $id"

  protected def stopSession(): Unit

  // Visible for testing.
  val proxyUser: Option[String]

  protected def doAsOwner[T](fn: => T): T = {
    val user = proxyUser.getOrElse(owner)
    if (user != null) {
      val ugi = if (UserGroupInformation.isSecurityEnabled) {
        if (livyConf.getBoolean(LivyConf.IMPERSONATION_ENABLED)) {
          UserGroupInformation.createProxyUser(user, UserGroupInformation.getCurrentUser())
        } else {
          UserGroupInformation.getCurrentUser()
        }
      } else {
        UserGroupInformation.createRemoteUser(user)
      }
      ugi.doAs(new PrivilegedExceptionAction[T] {
        override def run(): T = fn
      })
    } else {
      fn
    }
  }

  protected def copyResourceToHDFS(dataStream: InputStream, name: String): URI = doAsOwner {
    val fs = FileSystem.newInstance(livyConf.hadoopConf)

    try {
      val filePath = new Path(getStagingDir(fs), name)
      debug(s"Uploading user file to $filePath")

      val outFile = fs.create(filePath, true)
      val buffer = new Array[Byte](512 * 1024)
      var read = -1
      try {
        while ({read = dataStream.read(buffer); read != -1}) {
          outFile.write(buffer, 0, read)
        }
      } finally {
        outFile.close()
      }
      filePath.toUri
    } finally {
      fs.close()
    }
  }

  private def getStagingDir(fs: FileSystem): Path = synchronized {
    if (stagingDir == null) {
      val stagingRoot = Option(livyConf.get(LivyConf.SESSION_STAGING_DIR)).getOrElse {
        new Path(fs.getHomeDirectory(), ".livy-sessions").toString()
      }

      val sessionDir = new Path(stagingRoot, UUID.randomUUID().toString())
      fs.mkdirs(sessionDir)
      fs.setPermission(sessionDir, new FsPermission("700"))
      stagingDir = sessionDir
      debug(s"Session $id staging directory is $stagingDir")
    }
    stagingDir
  }

}
