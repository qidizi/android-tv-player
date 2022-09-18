+function () {
    // 防止重复注入
    if (window.hasQidiziTvPlayer) return;
    window.hasQidiziTvPlayer = true;
    setInterval(() => {
        try {
            let video = document.querySelector('video');
            let src = '';
            if (video) src = video.src;
            document.title = 'tv播放器.' + +new Date + ' ' + src;
        } catch (e) {
            alert('d ' + e);
        }
    }, 1000);
    //
    // try {
    //     // 关闭不是当前的tab https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions/API/tabs/query
    //     let tabs = browser.tabs.query({
    //         // 当前的不要
    //         active: false,
    //     }).then(tabs => {
    //         // tabs数组 https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions/API/tabs/Tab
    //         for (const tab of tabs) {
    //             // 关闭
    //             browser.tabs.remove(tab.id);
    //         }
    //     }, e => {
    //         alert('查询非激活的tab失败：' + e);
    //     });
    // } catch (e) {
    //     alert('出错 ' + e);
    // }
}();