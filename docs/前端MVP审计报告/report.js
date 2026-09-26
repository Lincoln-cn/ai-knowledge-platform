/* 角色筛选：高亮该角色在两张矩阵里的列，并只留他/她的业务链 */
(function () {
  'use strict';

  var COL = { admin: 1, zhangyun: 2, liwei: 3, chenjun: 4 };
  var ROWS = ['#reach', '#acts'];

  function applyRole(role) {
    var active = role && COL[role];

    ROWS.forEach(function (sel) {
      var table = document.querySelector(sel);
      if (!table) return;
      table.querySelectorAll('tr').forEach(function (tr) {
        var cells = tr.children;
        for (var i = 0; i < cells.length; i++) {
          var isRoleCol = i >= 1 && i <= 4;
          if (!isRoleCol) continue;
          var on = !active || i === active;
          cells[i].style.opacity = on ? '' : '0.22';
          cells[i].style.transition = 'opacity .15s';
        }
      });
    });

    document.querySelectorAll('#flows .flow').forEach(function (f) {
      f.style.display = !active || f.dataset.role === role ? '' : 'none';
    });

    document.querySelectorAll('#roleFilter button').forEach(function (b) {
      b.setAttribute('aria-pressed', String(b.dataset.role === (role || 'all')));
    });
  }

  document.querySelectorAll('#roleFilter button').forEach(function (b) {
    b.addEventListener('click', function () {
      var r = b.dataset.role;
      applyRole(r === 'all' ? null : r);
    });
  });

  /* 表格首列在窄屏上吸住，方便横向滚动时看得到行名 */
  ['#reach', '#acts'].forEach(function (sel) {
    var t = document.querySelector(sel);
    if (!t) return;
    t.querySelectorAll('tbody tr').forEach(function (tr) {
      if (tr.children[0]) {
        tr.children[0].style.position = 'sticky';
        tr.children[0].style.left = '0';
        tr.children[0].style.background = 'var(--surface)';
        tr.children[0].style.zIndex = '1';
      }
    });
  });
})();
