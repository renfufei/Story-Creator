/**
 * 全站导航栏（静态页共用）
 *
 * 用法：页面里放 <header id="site-nav"></header>，引入本脚本即可自动渲染。
 * 通过 <body data-nav="projects"> 指定当前高亮项（可选值见 NAV_ITEMS 的 key）。
 */
(function (global) {
    'use strict';

    var SC = global.SC || (global.SC = {});

    var NAV_ITEMS = [
        { key: 'projects', text: '项目列表', href: '/', icon: 'bi-journal-text' },
        { key: 'chat', text: '聊天', href: '/chat', icon: 'bi-chat-dots' },
        { key: 'tts', text: '语音导出', href: '/tts-export', icon: 'bi-megaphone' },
        { key: 'learn', text: '教学', href: '/learn', icon: 'bi-mortarboard' },
        { key: 'import', text: '导入项目', href: '/import/txt', icon: 'bi-upload' }
    ];

    var SETTING_ITEMS = [
        { text: 'AI模型配置', href: '/settings', icon: 'bi-cpu' },
        { text: '流程模板配置', href: '/prompts', icon: 'bi-diagram-3' },
        { text: 'TTS替换模板', href: '/settings/tts-templates', icon: 'bi-music-note-list' },
        { text: '创作指导库', href: '/settings/guidances', icon: 'bi-lightbulb' },
        { text: '素材库', href: '/settings/materials', icon: 'bi-collection' },
        { text: '章节分割配置', href: '/settings/chapter-split-configs', icon: 'bi-scissors' }
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
            return '<li><a class="dropdown-item" href="' + it.href + '">' +
                '<i class="bi ' + it.icon + '"></i> ' + esc(it.text) + '</a></li>';
        }).join('');

        var settingActive = SETTING_ITEMS.some(function (it) {
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
