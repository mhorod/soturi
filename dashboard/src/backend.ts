import Cookies from "js-cookie";

const backend = process.env.REACT_APP_SOTURI_BACKEND;

function get_url(): [string, string, string] {
  if (backend === "localhost") {
    return [
      `http://${window.location.hostname}:8080`,
      `ws://${window.location.hostname}:8080`,
      `http://${window.location.hostname}:8080`,
    ];
  } else if (backend === "production") {
    return [
      `https://soturi.online`,
      `wss://soturi.online`,
      `https://soturi.online`,
    ];
  } else {
    const protocol = window.location.protocol || "http:";
    const host = window.location.host;

    const ws_protocol = protocol === "http:" ? "ws" : "wss";
    return [`${protocol}//${host}`, `${ws_protocol}://${host}`, ""];
  }
}

const [HTTP_URL, WS_URL, REL_URL] = get_url();

export function http_path(path: string) {
  return HTTP_URL + path;
}

export function rel_path(path: string) {
  return REL_URL + path;
}

export function ws_path(path: string) {
  return WS_URL + "/ws" + path;
}

export async function get_json(path: string) {
  return fetch(http_path(path), {
    headers: {
      Authorization: "Bearer " + get_auth_token(),
    },
  }).then((res) => res.json());
}

export async function get_string(path: string) {
  return fetch(http_path(path), {
    headers: {
      Authorization: "Bearer " + get_auth_token(),
    },
  }).then((res) => res.text());
}

function get_auth_token() {
  return Cookies.get("token");
}
