/*
 * Copyright 2000-2010 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetbrains.android.ddms;

import com.intellij.openapi.util.SystemInfo;
import org.jetbrains.android.util.AndroidBundle;

/**
 * @author Eugene.Kudelevsky
 */
public class AdbNotRespondingException extends Exception {
  private final String myMessage;

  public AdbNotRespondingException() {
    String processName = SystemInfo.isWindows ? "adb.exe" : "adb";
    myMessage = AndroidBundle.message("android.debug.bridge.crashed.error", processName);
  }

  public String getMessage() {
    return myMessage;
  }
}
