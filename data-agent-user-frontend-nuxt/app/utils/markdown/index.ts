/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import MarkdownIt from 'markdown-it';
import MarkdownItContainer from 'markdown-it-container';
import highlightPlugin from './markdown-plugin-highlight';
import echartsPlugin from './markdown-plugin-echarts';

const md = new MarkdownIt({
	linkify: true,
	breaks: true,
	html: false,
})
	.use(highlightPlugin)
	.use(echartsPlugin)
	.use(MarkdownItContainer);

export interface MarkdownRenderOptions {
	streaming?: boolean;
}

function normalizeReportMarkdown(content: string): string {
	const lines = content.replace(/\r\n?/g, '\n').split('\n');
	let fenced = false;
	let fenceMarker = '';
	return lines
		.map((line) => {
			const fence = line.match(/^\s*(`{3,}|~{3,})/);
			if (fence) {
				if (!fenced) {
					fenced = true;
					fenceMarker = fence[1]![0]!;
				} else if (fence[1]![0] === fenceMarker) fenced = false;
				return line;
			}
			if (fenced) return line;
			return line
				.replace(/([^\s#])(#{1,6})(?=\S)/g, '$1\n$2 ')
				.replace(/^(\s*#{1,6})(?!#)(\S)/, '$1 $2')
				.replace(/^(\s*[-*+])(?![\s-])(\S)/, '$1 $2')
				.replace(/^(\s*\d+[.)])(\S)/, '$1 $2');
		})
		.join('\n');
}

export function renderMarkdownContent(
	content: string,
	options: MarkdownRenderOptions = {},
): string {
	if (!content) return '';
	return md.render(normalizeReportMarkdown(content), options);
}

export { md };
