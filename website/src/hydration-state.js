/**
 * Static prerendering cannot know a visitor's saved or operating-system theme.
 * Both SSR and the first client render must therefore use this exact state.
 * App.vue applies browser preferences after hydration completes.
 */
export const hydrationTheme = Object.freeze({
  preference: "system",
  system: "dark",
});
