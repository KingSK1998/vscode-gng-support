import * as vscode from "vscode";

export interface GspRegion {
  language: "html" | "css" | "javascript" | "groovy" | "comment" | "directive";
  startOffset: number;
  endOffset: number;
  startPosition: vscode.Position;
  endPosition: vscode.Position;
  isGroovy: boolean;
  text: string;
}

export class GspRegionParser {
  /**
   * Parses the given GSP document text into distinct language regions.
   * Accurately tracks nesting (Groovy interpolation inside CSS/JS/HTML tags),
   * directives, comments, CRLF/LF line breaks, and astral characters.
   */
  static parse(text: string): GspRegion[] {
    const regions: GspRegion[] = [];
    const n = text.length;
    let i = 0;

    // Helper to calculate position for an offset
    const getPos = (offset: number): vscode.Position => {
      let l = 0;
      let c = 0;
      for (let idx = 0; idx < offset; idx++) {
        if (text[idx] === "\n") {
          l++;
          c = 0;
        } else if (text[idx] !== "\r") {
          c++;
        }
      }
      return new vscode.Position(l, c);
    };

    let htmlStartOffset = 0;
    const flushHtml = (endOffset: number): void => {
      if (endOffset > htmlStartOffset) {
        const segText = text.substring(htmlStartOffset, endOffset);
        regions.push({
          language: "html",
          startOffset: htmlStartOffset,
          endOffset,
          startPosition: getPos(htmlStartOffset),
          endPosition: getPos(endOffset),
          isGroovy: false,
          text: segText,
        });
      }
      htmlStartOffset = endOffset;
    };

    while (i < n) {
      // 1. Comments %{-- ... --}%
      if (text.startsWith("%{--", i)) {
        flushHtml(i);
        const start = i;
        const endIdx = text.indexOf("--}%", i + 4);
        const end = endIdx !== -1 ? endIdx + 4 : n;
        regions.push({
          language: "comment",
          startOffset: start,
          endOffset: end,
          startPosition: getPos(start),
          endPosition: getPos(end),
          isGroovy: false,
          text: text.substring(start, end),
        });
        i = end;
        htmlStartOffset = end;
        continue;
      }

      // 2. Directives <%@ ... %>
      if (text.startsWith("<%@", i)) {
        flushHtml(i);
        const start = i;
        const endIdx = text.indexOf("%>", i + 3);
        const end = endIdx !== -1 ? endIdx + 2 : n;
        regions.push({
          language: "directive",
          startOffset: start,
          endOffset: end,
          startPosition: getPos(start),
          endPosition: getPos(end),
          isGroovy: false,
          text: text.substring(start, end),
        });
        i = end;
        htmlStartOffset = end;
        continue;
      }

      // 3. Scriptlets <% ... %> and Expressions <%= ... %>
      if (text.startsWith("<%", i)) {
        flushHtml(i);
        const start = i;
        const endIdx = text.indexOf("%>", i + 2);
        const end = endIdx !== -1 ? endIdx + 2 : n;
        regions.push({
          language: "groovy",
          startOffset: start,
          endOffset: end,
          startPosition: getPos(start),
          endPosition: getPos(end),
          isGroovy: true,
          text: text.substring(start, end),
        });
        i = end;
        htmlStartOffset = end;
        continue;
      }

      // 4. Interpolations ${ ... }
      if (text.startsWith("${", i)) {
        flushHtml(i);
        const start = i;
        let braceDepth = 1;
        let cur = i + 2;
        while (cur < n && braceDepth > 0) {
          if (text[cur] === "{") {
            braceDepth++;
          } else if (text[cur] === "}") {
            braceDepth--;
          }
          cur++;
        }
        const end = cur;
        regions.push({
          language: "groovy",
          startOffset: start,
          endOffset: end,
          startPosition: getPos(start),
          endPosition: getPos(end),
          isGroovy: true,
          text: text.substring(start, end),
        });
        i = end;
        htmlStartOffset = end;
        continue;
      }

      // 5. Embedded <style> ... </style>
      if (text.substring(i, i + 6).toLowerCase() === "<style") {
        const tagClose = text.indexOf(">", i + 6);
        if (tagClose !== -1) {
          flushHtml(tagClose + 1);
          const styleBodyStart = tagClose + 1;
          const styleEnd = text.toLowerCase().indexOf("</style>", styleBodyStart);
          const styleBodyEnd = styleEnd !== -1 ? styleEnd : n;

          // Parse style body with possible nested ${...} interpolations
          let sPos = styleBodyStart;
          while (sPos < styleBodyEnd) {
            const nextInterp = text.indexOf("${", sPos);
            if (nextInterp !== -1 && nextInterp < styleBodyEnd) {
              if (nextInterp > sPos) {
                regions.push({
                  language: "css",
                  startOffset: sPos,
                  endOffset: nextInterp,
                  startPosition: getPos(sPos),
                  endPosition: getPos(nextInterp),
                  isGroovy: false,
                  text: text.substring(sPos, nextInterp),
                });
              }
              // Nested Groovy interpolation
              let bDepth = 1;
              let c = nextInterp + 2;
              while (c < styleBodyEnd && bDepth > 0) {
                if (text[c] === "{") bDepth++;
                else if (text[c] === "}") bDepth--;
                c++;
              }
              regions.push({
                language: "groovy",
                startOffset: nextInterp,
                endOffset: c,
                startPosition: getPos(nextInterp),
                endPosition: getPos(c),
                isGroovy: true,
                text: text.substring(nextInterp, c),
              });
              sPos = c;
            } else {
              // Remaining style body is CSS
              regions.push({
                language: "css",
                startOffset: sPos,
                endOffset: styleBodyEnd,
                startPosition: getPos(sPos),
                endPosition: getPos(styleBodyEnd),
                isGroovy: false,
                text: text.substring(sPos, styleBodyEnd),
              });
              sPos = styleBodyEnd;
            }
          }
          i = styleEnd !== -1 ? styleEnd + 8 : n;
          htmlStartOffset = i;
          continue;
        }
      }

      // 6. Embedded <script> ... </script>
      if (text.substring(i, i + 7).toLowerCase() === "<script") {
        const tagClose = text.indexOf(">", i + 7);
        if (tagClose !== -1) {
          flushHtml(tagClose + 1);
          const scriptBodyStart = tagClose + 1;
          const scriptEnd = text.toLowerCase().indexOf("</script>", scriptBodyStart);
          const scriptBodyEnd = scriptEnd !== -1 ? scriptEnd : n;

          // Parse script body with possible nested ${...} interpolations
          let scPos = scriptBodyStart;
          while (scPos < scriptBodyEnd) {
            const nextInterp = text.indexOf("${", scPos);
            if (nextInterp !== -1 && nextInterp < scriptBodyEnd) {
              if (nextInterp > scPos) {
                regions.push({
                  language: "javascript",
                  startOffset: scPos,
                  endOffset: nextInterp,
                  startPosition: getPos(scPos),
                  endPosition: getPos(nextInterp),
                  isGroovy: false,
                  text: text.substring(scPos, nextInterp),
                });
              }
              // Nested Groovy interpolation
              let bDepth = 1;
              let c = nextInterp + 2;
              while (c < scriptBodyEnd && bDepth > 0) {
                if (text[c] === "{") bDepth++;
                else if (text[c] === "}") bDepth--;
                c++;
              }
              regions.push({
                language: "groovy",
                startOffset: nextInterp,
                endOffset: c,
                startPosition: getPos(nextInterp),
                endPosition: getPos(c),
                isGroovy: true,
                text: text.substring(nextInterp, c),
              });
              scPos = c;
            } else {
              // Remaining script body is JS
              regions.push({
                language: "javascript",
                startOffset: scPos,
                endOffset: scriptBodyEnd,
                startPosition: getPos(scPos),
                endPosition: getPos(scriptBodyEnd),
                isGroovy: false,
                text: text.substring(scPos, scriptBodyEnd),
              });
              scPos = scriptBodyEnd;
            }
          }
          i = scriptEnd !== -1 ? scriptEnd + 9 : n;
          htmlStartOffset = i;
          continue;
        }
      }

      i++;
    }

    flushHtml(n);
    return regions;
  }

  /**
   * Finds the region enclosing the given position.
   */
  static getRegionAt(regions: GspRegion[], position: vscode.Position): GspRegion | null {
    for (const r of regions) {
      if (position.isBefore(r.startPosition)) continue;
      if (position.isAfter(r.endPosition)) continue;
      return r;
    }
    return null;
  }
}
