import app from "./src/app.ts";

const port = 8000;
app.set("port", port);
app.listen(port, () => console.log("App is running :)"));
