// Ponte segura: a tela só enxerga estas funções (nada de acesso livre ao computador)
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('prismo', {
  http: (metodo, url, headers, corpo) => ipcRenderer.invoke('http', { metodo, url, headers, corpo }),
  sessaoLer: () => ipcRenderer.invoke('sessao:ler'),
  sessaoGravar: (s) => ipcRenderer.invoke('sessao:gravar', s),
  notificar: (titulo, texto) => ipcRenderer.invoke('notificar', titulo, texto),
  contador: (n) => ipcRenderer.invoke('contador', n),
  versao: () => ipcRenderer.invoke('versao'),
  abrirEmail: (email) => ipcRenderer.invoke('abrir-email', email),
  google: () => ipcRenderer.invoke('google:entrar'),
  googleCancelar: () => ipcRenderer.invoke('google:cancelar')
});
