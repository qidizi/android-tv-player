#!/bin/bash

# bash test/htmlLoopGet.sh

# 循环请求设备信息进行压力测试tvServer稳定性如何

_run() {
	local _i=0
	echo -e "\n\n\n"
	while true; do
		_i=$((_i + 1))
		_getHtml $_i || {
			echo "fail on loop $_i"
			break
		}

		echo -e "\n\n\n"
		##sleep 1
	done
}

_getHtml() {
	echo -e "\n\n\n["
	curl -vvv  http://10.10.10.3:8888/?action=getInfo
	local exitCode=$?
	echo -e "\n\n\n]-$1\n\n\n"
	return $exitCode
}

_run
