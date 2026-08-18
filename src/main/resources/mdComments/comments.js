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
        return el;
      }
      el = el.parentElement;
    }
    return null;
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

  function overlaps(range, ranges) {
    for (var i = 0; i < ranges.length; i++) {
      var r = ranges[i];
      if (range[0] <= r[1] && r[0] <= range[1]) return true;
    }
    return false;
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
    var matches = [];
    for (var j = 0; j < blocks.length; j++) {
      var range = parseRange(blocks[j].getAttribute(attrName));
      if (!range) continue;
      var mark = overlaps(range, commentedRanges) ? COMMENTED_CLASS
        : overlaps(range, resolvedRanges) ? RESOLVED_CLASS
        : null;
      if (mark) matches.push({ el: blocks[j], mark: mark });
    }
    // Mark only the innermost overlapping block per line. Container elements
    // (e.g. a wrapper div spanning the whole document) also carry the
    // position attribute and would otherwise swallow every marker, or stack
    // a second one when both parent and child match.
    for (var k = 0; k < matches.length; k++) {
      var hasMatchingDescendant = false;
      for (var m = 0; m < matches.length; m++) {
        if (m !== k && matches[k].el.contains(matches[m].el)) {
          hasMatchingDescendant = true;
          break;
        }
      }
      if (!hasMatchingDescendant) matches[k].el.classList.add(matches[k].mark);
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
