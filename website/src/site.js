import { createApp, createSSRApp } from "vue";
import App from "./App.vue";
import "./styles.css";

export function createClientApp(props) {
  return createApp(App, props);
}

export function createServerApp(props) {
  return createSSRApp(App, props);
}
