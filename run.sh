#!bash

function isTermux() {

  if [[ -d "/data/data/com.termux/files/" ]]; then
	  return 0
fi

return 1

}

function gradleBuild() {
  echo "gradle 路径 $(which gradle)"

  # 自动下载的aapt2不兼容
  # 提供指定路径和系统的aapt https://issuetracker.google.com/issues/195035561
  local taskArg=""
  if isTermux; then
    taskArg="-Pandroid.aapt2FromMavenOverride=/data/data/com.termux/files/usr/bin/aapt2"
    fi

  if gradle  run $taskArg; then
    if isTermux; then

        local apk="file://${shRoot}/build/outputs/apk/release/app-release.apk"
        # echo "$apk"
        am start -a "android.intent.action.INSTALL_PACKAGE" -d "$apk"
    else
      if adb install "$(getShDir)/build/outputs/apk/release/app-release.apk"; then
        echo "安装成功"
      fi
    fi
  fi
}

gradleBuild

