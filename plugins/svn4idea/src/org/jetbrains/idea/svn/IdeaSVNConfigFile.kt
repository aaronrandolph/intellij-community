// Copyright 2000-2017 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.idea.svn

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.Couple
import com.intellij.openapi.util.io.FileSystemUtil.lastModified
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.util.text.StringUtil.notNullize
import com.intellij.util.containers.ContainerUtil.union
import org.ini4j.Ini
import org.jetbrains.idea.svn.SvnUtil.createUrl
import org.jetbrains.idea.svn.api.Url
import org.jetbrains.idea.svn.commandLine.SvnBindException
import org.jetbrains.idea.svn.config.DefaultProxyGroup
import org.jetbrains.idea.svn.config.ProxyGroup
import org.tmatesoft.svn.core.internal.wc.DefaultSVNOptions
import java.io.IOException
import java.nio.file.Path
import java.util.*

private val LOG = logger<IdeaSVNConfigFile>()

class IdeaSVNConfigFile(private val myPath: Path) {

  private val myPatternsMap = mutableMapOf<String, String?>()
  private var myLatestUpdate = -1L
  private val myDefaultProperties = mutableMapOf<String, String?>()
  private val _configFile = Ini().apply {
    config.isTree = false
    config.isEmptySection = true
  }
  private val configFile: Ini
    get() {
      updateGroups()
      return _configFile
    }

  val allGroups: Map<String, ProxyGroup>
    get() {
      val result = HashMap<String, ProxyGroup>(myPatternsMap.size)
      for ((groupName, value) in myPatternsMap) {
        result.put(groupName, ProxyGroup(groupName, value, getValues(groupName)))
      }
      return result
    }

  val defaultGroup get() = DefaultProxyGroup(myDefaultProperties)

  @JvmOverloads
  fun updateGroups(force: Boolean = false) {
    val lastModified = lastModified(myPath.toFile())

    if (force || myLatestUpdate != lastModified) {
      _configFile.clear()
      try {
        _configFile.load(myPath.toFile())
      }
      catch (e: IOException) {
        LOG.info("Could not load $myPath", e)
      }
      myLatestUpdate = lastModified

      myPatternsMap.clear()
      myPatternsMap.putAll(getValues(GROUPS_GROUP_NAME))

      myDefaultProperties.clear()
      myDefaultProperties.putAll(getValues(DEFAULT_GROUP_NAME))
    }
  }

  fun getValue(groupName: String, propertyName: String): String? = configFile[groupName, propertyName]
  fun getValues(groupName: String): Map<String, String?> = configFile[groupName] ?: emptyMap()
  fun setValue(groupName: String, propertyName: String, value: String?) {
    configFile.put(groupName, propertyName, value)
  }

  fun deleteGroup(name: String) {
    if (DEFAULT_GROUP_NAME == name) {
      myDefaultProperties.clear()
    }
    // remove group from groups
    setValue(GROUPS_GROUP_NAME, name, null)
    configFile.remove(name)
  }

  fun addGroup(name: String, patterns: String?, properties: Map<String, String?>) {
    setValue(GROUPS_GROUP_NAME, name, patterns)
    addProperties(name, properties)
  }

  private fun addProperties(groupName: String, properties: Map<String, String?>) {
    for ((key, value) in properties) {
      setValue(groupName, key, value)
    }
  }

  fun modifyGroup(name: String, patterns: String?, delete: Collection<String>, addOrModify: Map<String, String?>, isDefault: Boolean) {
    if (!isDefault) {
      setValue(GROUPS_GROUP_NAME, name, patterns)
    }
    val deletedPrepared = HashMap<String, String?>(delete.size)
    for (property in delete) {
      deletedPrepared.put(property, null)
    }
    addProperties(name, deletedPrepared)
    addProperties(name, addOrModify)
  }

  fun save() = try {
    _configFile.store(myPath.toFile())
    updateGroups(true)
  }
  catch (e: IOException) {
    LOG.info("Could not save $myPath", e)
  }

  companion object {
    @JvmField
    val SERVERS_FILE_NAME = "servers"
    @JvmField
    val CONFIG_FILE_NAME = "config"

    @JvmField
    val DEFAULT_GROUP_NAME = "global"
    @JvmField
    val GROUPS_GROUP_NAME = "groups"

    @JvmStatic
    fun getNewGroupName(host: String, configFile: IdeaSVNConfigFile): String {
      var groupName = host
      val groups = configFile.allGroups
      while (StringUtil.isEmptyOrSpaces(groupName) || groups.containsKey(groupName)) {
        groupName += "1"
      }
      return groupName
    }

    @JvmStatic
    fun getPropertyIdea(host: String, serversFile: Couple<IdeaSVNConfigFile>, name: String): String? {
      val groupName = getGroupName(getValues(serversFile, GROUPS_GROUP_NAME), host)
      if (groupName != null) {
        val hostProps = getValues(serversFile, groupName)
        val value = hostProps[name]
        if (value != null) {
          return value
        }
      }
      return getValues(serversFile, DEFAULT_GROUP_NAME)[name]
    }

    @JvmStatic
    fun checkHostGroup(url: String, patterns: String?, exceptions: String?): Boolean {
      val svnurl: Url
      try {
        svnurl = createUrl(url)
      }
      catch (e: SvnBindException) {
        return false
      }

      val host = svnurl.host
      return matches(patterns, host) && !matches(exceptions, host)
    }

    private fun matches(pattern: String?, host: String): Boolean {
      val tokenizer = StringTokenizer(notNullize(pattern), ",")
      while (tokenizer.hasMoreTokens()) {
        val token = tokenizer.nextToken()
        if (DefaultSVNOptions.matches(token, host)) {
          return true
        }
      }
      return false
    }

    @JvmStatic
    fun getGroupForHost(host: String, serversFile: IdeaSVNConfigFile): String? {
      val groups = serversFile.allGroups
      for ((key, value) in groups) {
        if (matches(value.patterns, host)) return key
      }
      return null
    }

    private fun getGroupName(groups: Map<String, String?>, host: String): String? {
      for ((key, value) in groups) {
        if (matches(value, host)) return key
      }
      return null
    }

    @JvmStatic
    fun isTurned(value: String?) = value == null || "yes".equals(value, true) || "on".equals(value, true) || "true".equals(value, true)

    @JvmStatic
    fun getValue(files: Couple<IdeaSVNConfigFile>, groupName: String, propertyName: String) =
      files.second.getValue(groupName, propertyName) ?: files.first.getValue(groupName, propertyName)

    @JvmStatic
    fun getValues(files: Couple<IdeaSVNConfigFile>, groupName: String): Map<String, String?> =
      union(files.first.getValues(groupName), files.second.getValues(groupName))
  }
}