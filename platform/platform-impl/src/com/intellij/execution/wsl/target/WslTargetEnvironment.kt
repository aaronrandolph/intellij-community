// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.execution.wsl.target

import com.intellij.execution.ExecutionException
import com.intellij.execution.Platform
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.target.HostPort
import com.intellij.execution.target.TargetEnvironment
import com.intellij.execution.target.TargetEnvironmentAwareRunProfileState.TargetProgressIndicator
import com.intellij.execution.target.TargetPlatform
import com.intellij.execution.target.TargetedCommandLine
import com.intellij.execution.wsl.WSLCommandLineOptions
import com.intellij.execution.wsl.WSLDistribution
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.util.io.FileUtil
import java.io.IOException
import java.nio.file.Path
import java.util.*

class WslTargetEnvironment(wslRequest: WslTargetEnvironmentRequest,
                           private val distribution: WSLDistribution) : TargetEnvironment(wslRequest) {

  private val myUploadVolumes: MutableMap<UploadRoot, UploadableVolume> = HashMap()
  private val myDownloadVolumes: Map<DownloadRoot, DownloadableVolume> = HashMap()
  private val myTargetPortBindings: MutableMap<TargetPortBinding, Int> = HashMap()
  private val myLocalPortBindings: MutableMap<LocalPortBinding, HostPort> = HashMap()

  override val uploadVolumes: Map<UploadRoot, UploadableVolume>
    get() = Collections.unmodifiableMap(myUploadVolumes)
  override val downloadVolumes: Map<DownloadRoot, DownloadableVolume>
    get() = Collections.unmodifiableMap(myDownloadVolumes)
  override val targetPortBindings: Map<TargetPortBinding, Int>
    get() = Collections.unmodifiableMap(myTargetPortBindings)
  override val localPortBindings: Map<LocalPortBinding, HostPort>
    get() = Collections.unmodifiableMap(myLocalPortBindings)

  override val targetPlatform: TargetPlatform
    get() = TargetPlatform(Platform.UNIX, TargetPlatform.CURRENT.arch)

  init {
    for (uploadRoot in wslRequest.uploadVolumes) {
      val targetRoot: String? = toLinuxPath(uploadRoot.localRootPath.toAbsolutePath().toString())
      if (targetRoot != null) {
        myUploadVolumes[uploadRoot] = Volume(uploadRoot.localRootPath, targetRoot)
      }
    }
    for (targetPortBinding in wslRequest.targetPortBindings) {
      val theOnlyPort = targetPortBinding.target
      if (targetPortBinding.local != null && targetPortBinding.local != theOnlyPort) {
        throw UnsupportedOperationException("Local target's TCP port forwarder is not implemented")
      }
      myTargetPortBindings[targetPortBinding] = theOnlyPort
    }
    for (localPortBinding in wslRequest.localPortBindings) {
      val theOnlyPort = localPortBinding.local
      if (localPortBinding.target != null && localPortBinding.target != theOnlyPort) {
        throw UnsupportedOperationException("Local target's TCP port forwarder is not implemented")
      }
      myLocalPortBindings[localPortBinding] = HostPort("localhost", theOnlyPort)
    }
  }

  private fun toLinuxPath(localPath: String): String? {
    val linuxPath = distribution.getWslPath(localPath)
    if (linuxPath != null) {
      return linuxPath
    }
    return convertUncPathToLinux(localPath)
  }

  private fun convertUncPathToLinux(localPath: String): String? {
    val root: String = WSLDistribution.UNC_PREFIX + distribution.msId
    val winLocalPath = FileUtil.toSystemDependentName(localPath)
    if (winLocalPath.startsWith(root)) {
      val linuxPath = winLocalPath.substring(root.length)
      if (linuxPath.isEmpty()) {
        return "/"
      }
      if (linuxPath.startsWith("\\")) {
        return FileUtil.toSystemIndependentName(linuxPath)
      }
    }
    return null
  }

  @Throws(ExecutionException::class)
  override fun createProcess(commandLine: TargetedCommandLine, indicator: ProgressIndicator): Process {
    var line = GeneralCommandLine(commandLine.collectCommandsSynchronously())
    val options = WSLCommandLineOptions().setRemoteWorkingDirectory(commandLine.workingDirectory)
    line = distribution.patchCommandLine(line, null, options)
    return line.createProcess()
  }

  override fun shutdown() {}

  private inner class Volume(override val localRoot: Path, override val targetRoot: String) : UploadableVolume {

    @Throws(IOException::class)
    override fun resolveTargetPath(relativePath: String): String {
      val localPath = FileUtil.toCanonicalPath(FileUtil.join(localRoot.toString(), relativePath))
      return toLinuxPath(localPath)!!
    }

    @Throws(IOException::class)
    override fun upload(relativePath: String, targetProgressIndicator: TargetProgressIndicator, resolvedTargetPath: String) {
    }
  }
}
