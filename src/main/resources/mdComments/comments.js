(function () {
  var EVENT_CLICKED = 'mdCommentClicked';
  var STATE_REQUEST_EVENT = 'mdCommentStateRequest';
  var STATE_EVENT = 'mdCommentEnabled';
  var RANGES_EVENT = 'mdCommentRanges';
  var RESOLVED_EVENT = 'mdCommentResolvedRanges';
  var EMOJI_EVENT = 'mdCommentEmojiState';
  var EMOJI_CLASS = 'md-show-emoji';
  var HOVER_CLASS = 'md-comment-hover';
  var COMMENTED_CLASS = 'md-commented';
  var RESOLVED_CLASS = 'md-comment-resolved';

  // Disabled until the IDE confirms otherwise; the Kotlin side owns the
  // persisted flag and pushes it here on load and whenever it's toggled.
  var enabled = false;

  // Source-offset ranges [[start,end],...] of already-commented lines,
  // pushed by the Kotlin side. Blocks overlapping one get COMMENTED_CLASS.
  var commentedRanges = [];
  // Same shape, but for lines whose comments are all resolved (shown with
  // a different marker, e.g. ✅ instead of 💬).
  var resolvedRanges = [];

  function getLineAttrName() {
    // The Markdown plugin tells us, at runtime, which attribute on rendered
    // elements holds the source position (used internally for
    // editor<->preview scroll sync). Reading it here instead of hardcoding
    // it means this keeps working even if JetBrains renames it later.
    var meta = document.querySelector('meta[name="markdown-position-attribute-name"]');
    return meta ? meta.getAttribute('content') : 'data-source-line';
  }

  function findLineElement(target, attrName) {
    var el = target;
    while (el && el !== document.body) {
      if (el.hasAttribute && el.hasAttribute(attrName)) {
        break;
      }
      el = el.parentElement;
    }
    if (!el || el === document.body) return null;

    var range = parseRange(el.getAttribute(attrName));
    // Expand to the outermost element covering the same source block, so a
    // paragraph of inline links hovers as one line instead of one word per
    // link (every AST element, inline included, carries the position attr).
    // Stop at the document wrapper (body's first child), which spans the
    // whole file.
    var root = document.body.firstChild;
    if (!root || !(root.hasAttribute && root.hasAttribute(attrName))) return el;
    while (el.parentElement && el.parentElement !== document.body && el.parentElement !== root) {
      var parent = el.parentElement;
      if (!(parent.hasAttribute && parent.hasAttribute(attrName))) break;
      var pr = parseRange(parent.getAttribute(attrName));
      if (pr && range && pr[0] <= range[0] && range[1] <= pr[1]) {
        el = parent;
        range = pr;
      } else {
        break;
      }
    }
    return el;
  }

  function extractOffset(rawValue) {
    // The position attribute holds a source character-offset range
    // "from..to" (that's how the bundled ScrollSync.js consumes it).
    // Take the start offset; Kotlin maps it back to a source line.
    var match = rawValue && rawValue.match(/\d+/);
    return match ? match[0] : null;
  }

  function parseRange(rawValue) {
    // Full "from..to" range from the position attribute.
    var match = rawValue && rawValue.match(/(\d+)\D+(\d+)/);
    return match ? [parseInt(match[1], 10), parseInt(match[2], 10)] : null;
  }

  // The preview re-renders blocks incrementally as the source is edited,
  // wiping our classes, so this must be repeatable and idempotent.
  function applyCommentedMarks(attrName) {
    var els = document.querySelectorAll('.' + COMMENTED_CLASS + ', .' + RESOLVED_CLASS);
    for (var i = 0; i < els.length; i++) {
      els[i].classList.remove(COMMENTED_CLASS);
      els[i].classList.remove(RESOLVED_CLASS);
    }
    if (!commentedRanges.length && !resolvedRanges.length) return;
    var blocks = document.querySelectorAll('[' + attrName + ']');
    var candidates = [];
    for (var j = 0; j < blocks.length; j++) {
      var range = parseRange(blocks[j].getAttribute(attrName));
      if (!range) continue;
      // Only elements fully inside one commented/resolved source line count.
      // +1 slack absorbs a trailing newline in the element's range. Anything
      // spanning beyond its line (the whole-document wrapper) never qualifies.
      var owner = null;
      var mark = null;
      for (var c = 0; c < commentedRanges.length; c++) {
        var cr = commentedRanges[c];
        if (range[0] >= cr[0] && range[1] <= cr[1] + 1) { owner = cr; mark = COMMENTED_CLASS; break; }
      }
      if (!mark) {
        for (var r = 0; r < resolvedRanges.length; r++) {
          var rr = resolvedRanges[r];
          if (range[0] >= rr[0] && range[1] <= rr[1] + 1) { owner = rr; mark = RESOLVED_CLASS; break; }
        }
      }
      if (mark) candidates.push({ el: blocks[j], range: range, mark: mark, owner: owner });
    }
    // Mark only the outermost element per source line. A paragraph containing
    // inline links renders the links as separate elements that also carry the
    // position attribute, so marking every candidate would paint each word
    // instead of the whole line. Any candidate whose range is contained in
    // another candidate on the same line (or the same range but nested) is a
    // sub-element and stays unmarked.
    for (var k = 0; k < candidates.length; k++) {
      var outermost = true;
      for (var m = 0; m < candidates.length; m++) {
        if (k === m || candidates[k].owner !== candidates[m].owner) continue;
        var a = candidates[k].range;
        var b = candidates[m].range;
        var nested = b[0] <= a[0] && a[1] <= b[1] &&
          (b[0] !== a[0] || b[1] !== a[1] || candidates[m].el.contains(candidates[k].el));
        if (nested) { outermost = false; break; }
      }
      if (outermost) candidates[k].el.classList.add(candidates[k].mark);
    }
  }

  function clearHover() {
    var hovered = document.querySelectorAll('.' + HOVER_CLASS);
    for (var i = 0; i < hovered.length; i++) {
      hovered[i].classList.remove(HOVER_CLASS);
    }
  }

  function init() {
    var attrName = getLineAttrName();

    if (window.__IntelliJTools && window.__IntelliJTools.messagePipe) {
      window.__IntelliJTools.messagePipe.subscribe(STATE_EVENT, function (data) {
        enabled = data === 'true';
        if (!enabled) clearHover();
      });
      window.__IntelliJTools.messagePipe.subscribe(RANGES_EVENT, function (data) {
        try {
          commentedRanges = JSON.parse(data) || [];
        } catch (e) {
          commentedRanges = [];
        }
        applyCommentedMarks(attrName);
      });
      window.__IntelliJTools.messagePipe.subscribe(EMOJI_EVENT, function (data) {
        document.body.classList.toggle(EMOJI_CLASS, data === 'true');
      });
      window.__IntelliJTools.messagePipe.subscribe(RESOLVED_EVENT, function (data) {
        try {
          resolvedRanges = JSON.parse(data) || [];
        } catch (e) {
          resolvedRanges = [];
        }
        applyCommentedMarks(attrName);
      });
      // The pipe can only reach the IDE after its query function is injected
      // (signalled by IdeReady). Posting earlier is silently dropped, which
      // leaves highlighting off on fresh page loads until a manual toggle.
      var requestState = function () {
        window.__IntelliJTools.messagePipe.post(STATE_REQUEST_EVENT, '');
      };
      if (window.__IntelliJTools.___jcefMessagePipePostToIdeFunction) {
        requestState();
      } else {
        window.addEventListener('IdeReady', requestState, { once: true });
      }
    }

    document.body.addEventListener('mouseover', function (e) {
      if (!enabled) return;
      var el = findLineElement(e.target, attrName);
      clearHover();
      if (el) el.classList.add(HOVER_CLASS);
    });

    document.body.addEventListener('mouseleave', clearHover);

    // Incremental preview re-renders replace DOM nodes and drop our marker
    // classes. Debounce re-application on any content mutation.
    var markTimer = null;
    new MutationObserver(function () {
      if (markTimer !== null) return;
      markTimer = setTimeout(function () {
        markTimer = null;
        applyCommentedMarks(attrName);
      }, 100);
    }).observe(document.body, { childList: true, subtree: true });

    document.body.addEventListener('click', function (e) {
      var el = findLineElement(e.target, attrName);
      if (!el) return;

      // Don't hijack real links/buttons rendered in the content.
      if (e.target.closest('a, button')) return;

      var offset = extractOffset(el.getAttribute(attrName));
      if (offset === null) return;

      if (window.__IntelliJTools && window.__IntelliJTools.messagePipe) {
        window.__IntelliJTools.messagePipe.post(EVENT_CLICKED, offset);
      }
    });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
