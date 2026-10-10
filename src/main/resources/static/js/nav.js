/**
 * 全站导航栏（静态页共用）
 *
 * 用法：页面里放 <header id="site-nav"></header>，引入本脚本即可自动渲染。
 * 通过 <body data-nav="..."> 指定当前高亮项，取值是 NAV_ITEMS 的 key：home / projects / learn / chat；
 * 「设置」下拉不看 data-nav，靠 SETTING_ITEMS 的路径前缀自动高亮。
 *
 * ⚠️ data-nav 一旦有值，isActive() 就只认它 —— NAV_ITEMS 里除命中的那一项外一律不高亮。
 * 所以页面要写「自己所属的一级入口」，而不是自己的功能名：
 * 语音导出 / 导入项目 / TXT 导入 / 逆向工程 都归「故事创作」⇒ data-nav="projects"。
 */
(function (global) {
    'use strict';

    var SC = global.SC || (global.SC = {});

    // 只放「一级入口」：首页 + 首页那三张模块卡（AI教学 / AI聊天 / 故事创作）+ 设置下拉。
    // 二级入口一律从这里下线，改由「首页卡片」或所属模块的页面进入 ——
    // 语音导出 / 导入项目 已挪进 /projects 页头的按钮组，故不再占导航位。
    //
    // 顺序（2026-10-09 定稿）：首页 → AI教学 → AI聊天 → 故事创作 → 设置▾。
    // 「AI」前缀是**导航栏专有文案**：首页那两张卡片的标题仍是「教学」「聊天」，卡序也没动
    // （用户明确要求只改导航栏），别看到两边不一致就去「顺手统一」。
    var NAV_ITEMS = [
        { key: 'home', text: '首页', href: '/', icon: 'bi-house' },
        { key: 'learn', text: 'AI教学', href: '/learn', icon: 'bi-mortarboard' },
        { key: 'chat', text: 'AI聊天', href: '/chat', icon: 'bi-chat-dots' },
        { key: 'projects', text: '故事创作', href: '/projects', icon: 'bi-journal-text' }
    ];

    var SETTING_ITEMS = [
        { text: 'AI模型配置', href: '/settings', icon: 'bi-cpu' },
        { text: '流程模板配置', href: '/prompts', icon: 'bi-diagram-3' },
        { text: 'TTS替换模板', href: '/settings/tts-templates', icon: 'bi-music-note-list' },
        { text: '创作指导库', href: '/settings/guidances', icon: 'bi-lightbulb' },
        { text: '素材库', href: '/settings/materials', icon: 'bi-collection' },
        { text: '章节分割配置', href: '/settings/chapter-split-configs', icon: 'bi-scissors' },
        // 外链：本站唯一一个「跳出站点」的入口（2026-10-09 加）。
        // external:true ⇒ 渲染时补 target="_blank" + rel="noopener noreferrer"，并在它前面插一条分隔线分组。
        // ⚠️ 内链别跟着加 external —— 那会让设置页在新标签页里重开一份。
        { text: 'Github项目', href: 'https://github.com/renfufei/Story-Creator', icon: 'bi-github', external: true }
    ];

    function build(settingText) {
        var esc = SC.escape;
        var active = document.body ? (document.body.getAttribute('data-nav') || '') : '';
        var path = global.location.pathname || '';

        function isActive(item) {
            if (active && item.key === active) return true;
            if (active) return false;
            if (item.href === '/') return path === '/' || path === '/index.html';
            return path === item.href || path.indexOf(item.href + '/') === 0;
        }

        var li = NAV_ITEMS.map(function (it) {
            return '<li class="nav-item">' +
                '<a class="nav-link' + (isActive(it) ? ' active' : '') + '" href="' + it.href + '">' +
                '<i class="bi ' + it.icon + '"></i> ' + esc(it.text) + '</a></li>';
        }).join('');

        var drop = SETTING_ITEMS.map(function (it) {
            // 外链（external:true）：单独用一条分隔线跟上面的内链分组，并另开标签页打开。
            // rel="noopener noreferrer" 必带 —— 别让新页面通过 window.opener 反控本页。
            return '<li>' + (it.external ? '<hr class="dropdown-divider">' : '') +
                '<a class="dropdown-item" href="' + it.href + '"' +
                (it.external ? ' target="_blank" rel="noopener noreferrer"' : '') + '>' +
                '<i class="bi ' + it.icon + '"></i> ' + esc(it.text) +
                (it.external ? ' <i class="bi bi-box-arrow-up-right ms-1 small"></i>' : '') +
                '</a></li>';
        }).join('');

        var settingActive = SETTING_ITEMS.some(function (it) {
            if (it.external) return false;   // 外链不是站内页面，永远不高亮
            return path === it.href || path.indexOf(it.href + '/') === 0;
        });

        return '' +
            '<nav class="navbar navbar-expand-lg navbar-dark bg-dark">' +
            '  <div class="container">' +
            '    <a class="navbar-brand" href="/"><i class="bi bi-book"></i> AI故事创作</a>' +
            '    <button class="navbar-toggler" type="button" data-bs-toggle="collapse" data-bs-target="#scNavBar">' +
            '      <span class="navbar-toggler-icon"></span></button>' +
            '    <div class="collapse navbar-collapse" id="scNavBar">' +
            '      <ul class="navbar-nav ms-auto align-items-lg-center">' + li +
            '        <li class="nav-item dropdown">' +
            '          <a class="nav-link dropdown-toggle' + (settingActive ? ' active' : '') + '" href="#" role="button" ' +
            '             data-bs-toggle="dropdown" aria-expanded="false">' + esc(settingText || '设置') + '</a>' +
            '          <ul class="dropdown-menu dropdown-menu-end">' + drop + '</ul>' +
            '        </li>' +
            '      </ul>' +
            '    </div>' +
            '  </div>' +
            '</nav>';
    }

    SC.nav = {
        mount: function (opts) {
            var host = document.getElementById('site-nav');
            if (!host) return;
            host.innerHTML = build(opts && opts.settingText);
        },
        items: NAV_ITEMS,
        settingItems: SETTING_ITEMS
    };

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', function () { SC.nav.mount(); });
    } else {
        SC.nav.mount();
    }

})(window);
