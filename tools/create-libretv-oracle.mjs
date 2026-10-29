import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const parser = await import(pathToFileURL(resolve('third_party/LibreTV/cms-parser.ts')));
const source = { key: 'sample', name: '样本源', url: 'https://cms.example/api.php/provide/vod' };
const cases = [];
for (const input of [
  '', '第1集$https://cdn.test/1.m3u8#第2集$https://cdn.test/2.m3u8',
  '正片$https://cdn.test/share/a$$$正片$https://cdn.test/a.m3u8?token=abc',
  '$$$正片$https://cdn.test/a.m3u8', '正片$https://cdn.test/a.mp4$$$备用$https://cdn.test/b.mp4',
  '无链接#错误$javascript:bad#本地$file:///bad#正片$https://cdn.test/ok.m3u8',
]) cases.push({ kind: 'episodes', input, expected: parser.extractEpisodesFromPlayUrl(input) });

for (const [query, titles] of [
  ['三体', ['三体', '三体 动画版', '体育新闻', '']],
  ['摔跤吧！爸爸', ['摔跤吧!爸爸', '爸爸去哪儿', '爸爸', '']],
  ['钢铁侠 2008', ['钢铁侠 2008', '2008钢铁侠', '钢铁侠 2009']],
  ['庆余年2', ['庆余年 第二季', '庆余年 第二季粤语', '庆余年 第十二季', '庆余年 第一季']],
  ['庆余年 第二季', ['庆余年 2', '庆余年 第十二季', '庆余年 第二季【解说】']],
  ['电影2', ['电影2', '电影12', '电影20']],
  ['片名 2', ['片名 2', '片名 12', '片名 第二季', '片名2']],
  ['ABC', ['abc', 'A.B.C', 'xyz']],
  ['', ['', '任意片名']],
  ['不存在的片名', ['体育目录', '默认推荐']],
]) cases.push({ kind: 'relevance', query, titles, expected: parser.filterRelevantResults(titles.map(name => ({ name })), query).map(x => x.name) });

for (const input of [
  { list: [{ vod_id: 12, vod_name: '三体', vod_pic: 'https://img.test/a.jpg', type_name: '国产剧', vod_year: '2023', vod_area: '中国', vod_remarks: '全30集' }] },
  { list: [{ vod_id: 0 }, { vod_id: null, vod_year: 2023 }] }, { list: null }, {},
]) cases.push({ kind: 'search', input, expected: parser.parseSearchList(input, source) });

for (const input of [
  { list: [{ vod_name: ' 三体 ', vod_pic: 'https://img.test/a.jpg', vod_content: '<p>简介</p>', vod_year: '2023', vod_area: '中国', vod_actor: '演员', vod_remarks: '全30集', vod_play_url: '第1集$https://cdn.test/1.m3u8#第2集$https://cdn.test/2.m3u8' }] },
  { list: [{ vod_name: '简介兜底', vod_content: '正片$https://cdn.test/fallback.m3u8', vod_play_url: '' }] },
]) cases.push({ kind: 'detail', input, expected: parser.parseDetail(input, source) });

mkdirSync('app/src/test/resources', { recursive: true });
writeFileSync('app/src/test/resources/libretv-v2.21.1-oracle.json', JSON.stringify({ source, upstream: { version: '2.21.1', commit: '29cbeb3d37b4876f909a0354087a3531af0447f6' }, cases }, null, 2) + '\n');
console.log(JSON.stringify({ cases: cases.length, origin: 'actual pinned LibreTV TypeScript functions' }));
