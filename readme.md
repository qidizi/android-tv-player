# 安卓视频播放器   

## 功能

由安卓电视app + 浏览器html组成 
 
1. 手机扫码推送视频url给tv播放（支持代理）
2. 暂停/播放
3. 增/减/静音（app须当前窗口）
4. 注入网络js。如通过js使用在线列表功能；get/head他源text（支持代理，如获取第三方列表、检测源可用性）。
5. 进度跳转
6. 列表管理（浏览器端）
7. 配置导入/导出
8. 遥控器：确认为播放/暂停、左右为后退/快进
9. 支持外置html

## 使用方法   
1. [从releases下载最新版本](https://github.com/qidizi/android-tv-player/releases);    
2. 安装至安卓电视;
3. 运行本应用;  
4. 微信扫码后使用;  

## 设备要求
android 5.0+
  
## 效果图        

<img src="screenshot.jpg" alt="效果图" height="400" />  

## 编译

1. android studio

直接点击run按钮

2. termux

cd到本目录，运行 `bash run.sh` 