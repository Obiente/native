import { createClientApp, createServerApp } from "./site.js";

const prerenderedProps = window.__NEXTCLOUD_NATIVE_SITE__;
const props = prerenderedProps ?? {
  initialPath: window.location.pathname,
};

if (!prerenderedProps && window.location.pathname !== "/") {
  const { docsContent } = await import("./generated/docs-content.js");
  const { guidesContent } = await import("./generated/guides-content.js");
  const { news } = await import("./generated/news.js");
  const normalizedPath = `/${window.location.pathname.replace(/^\/|\/$/g, "")}/`;
  props.initialDoc = docsContent.find((doc) => doc.path === normalizedPath) ?? null;
  props.initialGuide = guidesContent.find((guide) => guide.path === normalizedPath) ?? null;
  props.initialNews = news.find((post) => post.path === normalizedPath) ?? null;
}

const app = prerenderedProps ? createServerApp(props) : createClientApp(props);
app.mount("#app");
